#!/usr/bin/env python3
"""geo_import - loads JanNet AI's own geographic dataset into MySQL.

No map API key is needed at run time: every "where is this point?" answer
(PIN, village / town, Gram Panchayat / Nagar Nigam, ward) comes from the
tables this tool fills (V34 + V36 migrations).

The tool only WRITES SQL to stdout; pipe it into the MySQL client. That keeps
it standard-library only (Python 3.9+) and lets the database credentials stay
inside the MySQL container:

    python3 geo_import.py lgd --dir ~/jannet-geo-data/raw/lgd-2026-10-11 \\
        --states 9,10 --source-date 2026-10-11 \\
      | docker compose -f docker/docker-compose.yml exec -T mysql \\
          sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'

A progress / problem report goes to stderr. Every run is recorded in
geo_data_imports. Re-running with a newer download updates rows in place
(INSERT ... ON DUPLICATE KEY UPDATE); LGD codes are the keys and never change.

Sub-commands (see README.md for the full walk-through):
  lgd          LGD daily dump (ramSeraph/opendata "lgd-latest" CSVs):
               states, districts, sub-districts, blocks, villages,
               Gram Panchayats, urban bodies, village -> body, village -> PIN,
               urban body -> PIN, wards
  postoffices  India Post "All India Pincode Directory" CSV (with lat/long)
  boundaries   GeoJSON-lines polygons (.geojsonl or .geojsonl.7z):
               --layer WARD       Swachh Bharat Mission ULB ward boundaries
               --layer VILLAGE    Bharatmaps village boundaries (LGD codes)
               --layer PANCHAYAT  Bharatmaps Gram Panchayat boundaries
               --layer PINCODE    data.gov.in PIN code boundaries
               --layer LOCAL_BODY Swachh Bharat Mission ULB boundaries
  centres      sets village / ward map centres from the loaded polygons

Data honesty rules (project spec 2026-10-11):
  * nothing is invented: a row is written only when the source has it;
  * a link the source does not give by code (e.g. an SBM town without a
    Census 2011 code) is made only on an exact normalised name match inside
    the same district, and listed on stderr; anything else is skipped and
    listed - never guessed;
  * every row carries its source and verification status (PUBLISHED =
    published by a government portal, not yet checked by our staff).
"""
from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import json
import re
import subprocess
import sys
import unicodedata
from pathlib import Path
from typing import Iterable, Iterator

CSV_FIELD_LIMIT = 1 << 27
csv.field_size_limit(CSV_FIELD_LIMIT)

COMMIT_EVERY = 500

# LGD urban body type codes (same names as V35 so rows stay consistent).
URBAN_TYPE_NAMES = {
    "4": "Municipal Corporation",      # Nagar Nigam
    "5": "Municipality",               # Nagar Palika Parishad
    "6": "Notified Area Council",
    "7": "Town Panchayat",             # Nagar Panchayat
    "8": "Municipal Council",
    "21": "NCT Municipal Council",
    "24": "City Municipal Council",
    "25": "Town Municipal Council",
}
GP_TYPE_CODE = "3"   # LGD "Gram Panchayat" (village level PRI body)
GP_BODY_TYPE = "Village Panchayat"  # body_type text already used by V35 rows


# ---------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------

def report(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def name_key(name: str | None) -> str:
    """Same rule as backend GeoNames.normalize(): ASCII a-z0-9 only."""
    text = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]", "", text.lower())


# words that name the TYPE of an urban body, not the body itself. "Cantt" /
# "Cantonment" is NOT one of them: a Cantonment Board is a different body from
# the city's Nagar Nigam (Bareilly Cantt is not Bareilly).
_URBAN_WORDS = re.compile(
    r"\b(nagar\s*palika\s*parishad|nagar\s*panchayat|nagar\s*nigam|municipal\s*corporation|"
    r"municipal\s*council|municipality|notified\s*area|npp|np|nn|m\s*corp|m\s*ci|mcorp)\b", re.I)

# type written in a source name -> LGD urban type code (Nagar Nigam 4,
# Nagar Palika Parishad 5, Nagar Panchayat 7)
_TYPE_WORDS = [
    (re.compile(r"\b(nagar\s*nigam|municipal\s*corporation|nn|m\s*corp|mcorp)\b", re.I), "4"),
    (re.compile(r"\b(nagar\s*palika\s*parishad|npp|municipality|municipal\s*council)\b", re.I), "5"),
    (re.compile(r"\b(nagar\s*panchayat|np)\b", re.I), "7"),
]


def written_type(name: str | None) -> str | None:
    """'Npp Khoda Makanpur' -> '5'; None when the name carries no type word."""
    for rx, code in _TYPE_WORDS:
        if rx.search(name or ""):
            return code
    return None


def town_key(name: str | None) -> str:
    """Name of a town without its body-type words: 'Npp Khoda Makanpur' -> 'khodamakanpur'."""
    text = re.sub(r"\(.*?\)", " ", name or "")
    return name_key(_URBAN_WORDS.sub(" ", text))


def q(value) -> str:
    """SQL literal."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    s = str(value).replace("\\", "\\\\").replace("'", "''").replace("\n", " ").replace("\r", " ")
    return "'" + s + "'"


def clean(value: str | None, limit: int) -> str | None:
    if value is None:
        return None
    v = " ".join(str(value).split())
    if not v:
        return None
    return v[:limit]


def as_int(value) -> int | None:
    try:
        v = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return v if v > 0 else None


def norm_header(h: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (h or "").lower().replace("(inenglish)", "").replace("in english", ""))


def read_csv(path: Path) -> Iterator[dict]:
    """Rows with normalised header keys ('Local Body Code' -> 'localbodycode')."""
    with path.open(encoding="utf-8-sig", errors="replace", newline="") as f:
        reader = csv.reader(f)
        header = [norm_header(h) for h in next(reader)]
        for row in reader:
            yield dict(zip(header, row))


def find_file(directory: Path, stem: str) -> Path:
    """'villages' -> villages.11Oct2026.csv (newest) in the LGD folder."""
    hits = sorted(p for p in directory.glob(stem + ".*.csv") if p.name.split(".")[0] == stem)
    if not hits:
        hits = sorted(directory.glob(stem + ".csv"))
    if not hits:
        raise SystemExit(f"{stem}.*.csv not found in {directory} - unpack the .7z files first (7z x)")
    return hits[-1]


def geojson_lines(path: Path) -> Iterator[dict]:
    """Features of a .geojsonl file, or of a .geojsonl.7z streamed through 7z (no temp file)."""
    if path.suffix == ".7z":
        proc = subprocess.Popen(["7z", "x", "-so", str(path)], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
        assert proc.stdout is not None
        try:
            for line in proc.stdout:
                if line.strip():
                    yield json.loads(line)
        finally:
            proc.stdout.close()
            if proc.wait() != 0:
                raise SystemExit(f"7z could not read {path} (is p7zip installed? sudo apt install p7zip-full)")
    else:
        with path.open(encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.strip():
                    yield json.loads(line)


class Sql:
    """Writes statements to stdout in batches inside transactions."""

    def __init__(self) -> None:
        self.out = sys.stdout
        self.pending = 0
        self.total = 0

    def raw(self, text: str) -> None:
        self.out.write(text.rstrip() + "\n")

    def stmt(self, text: str) -> None:
        if self.pending == 0:
            self.out.write("START TRANSACTION;\n")
        self.out.write(text.rstrip().rstrip(";") + ";\n")
        self.pending += 1
        self.total += 1
        if self.pending >= COMMIT_EVERY:
            self.commit()

    def commit(self) -> None:
        if self.pending:
            self.out.write("COMMIT;\n")
            self.pending = 0

    def begin_import(self, dataset: str, source: str, source_date: str | None, scope: str) -> None:
        self.commit()
        self.raw("SET NAMES utf8mb4;")
        self.raw(f"INSERT INTO geo_data_imports (dataset, source, source_date, scope, row_count) "
                 f"VALUES ({q(dataset)}, {q(source[:200])}, {q(source_date)}, {q(scope[:200])}, 0);")
        self.raw("SET @imp = LAST_INSERT_ID();")

    def end_import(self, count_sql: str | None = None, count: int | None = None) -> None:
        self.commit()
        if count_sql:
            self.raw(f"UPDATE geo_data_imports SET row_count = ({count_sql}) WHERE import_id = @imp;")
        else:
            self.raw(f"UPDATE geo_data_imports SET row_count = {int(count or 0)} WHERE import_id = @imp;")


def parse_states(text: str) -> set[int]:
    states = {int(s) for s in text.split(",") if s.strip()}
    if not states:
        raise SystemExit("--states needs LGD state codes, e.g. 9,10 (9 = Uttar Pradesh, 10 = Bihar)")
    return states


# ---------------------------------------------------------------------------
# LGD
# ---------------------------------------------------------------------------

def cmd_lgd(args: argparse.Namespace) -> None:
    lgd = Path(args.dir).expanduser()
    states = parse_states(args.states)
    serve = {int(s) for s in (args.serve or "").split(",") if s.strip()}
    if args.gp_wards == "all":
        gp_ward_districts = None                       # every district
    elif args.gp_wards == "none":
        gp_ward_districts = set()
    elif args.gp_wards == "served":
        gp_ward_districts = serve | {int(s) for s in (args.served_before or "").split(",") if s.strip()}
    else:
        gp_ward_districts = {int(s) for s in args.gp_wards.split(",") if s.strip()}
    date = args.source_date
    sql = Sql()
    scope = "states " + ",".join(str(s) for s in sorted(states))
    sql.begin_import("LGD", str(lgd), date, scope)

    # --- states + districts: all of India (small; needed for names everywhere)
    n = 0
    for r in read_csv(find_file(lgd, "states")):
        code = as_int(r.get("statecode"))
        if code:
            sql.stmt("INSERT INTO geo_states (state_code, name, census_code) VALUES "
                     f"({code}, {q(clean(r.get('statenameinenglish') or r.get('statename'), 100))}, "
                     f"{q(clean(r.get('census2011code'), 10))}) "
                     "ON DUPLICATE KEY UPDATE name = VALUES(name), census_code = VALUES(census_code)")
            n += 1
    report(f"states: {n}")
    n = 0
    district_names: dict[int, dict[str, int]] = collections.defaultdict(dict)
    for r in read_csv(find_file(lgd, "districts")):
        code, st = as_int(r.get("districtcode")), as_int(r.get("statecode"))
        name = clean(r.get("districtnameinenglish") or r.get("districtname"), 100)
        if code and st and name:
            sql.stmt("INSERT INTO geo_districts (district_code, state_code, name, census_code) VALUES "
                     f"({code}, {st}, {q(name)}, {q(clean(r.get('census2011code'), 10))}) "
                     "ON DUPLICATE KEY UPDATE state_code = VALUES(state_code), name = VALUES(name), "
                     "census_code = VALUES(census_code)")
            district_names[st][name_key(name)] = code
            n += 1
    report(f"districts: {n}")

    # --- sub-districts + blocks of the chosen states
    n = 0
    for r in read_csv(find_file(lgd, "subdistricts")):
        if as_int(r.get("statecode")) not in states:
            continue
        code, d = as_int(r.get("subdistrictcode")), as_int(r.get("districtcode"))
        if code and d:
            sql.stmt("INSERT INTO geo_subdistricts (subdistrict_code, district_code, name, census_code) VALUES "
                     f"({code}, {d}, {q(clean(r.get('subdistrictname'), 100))}, "
                     f"{q(clean(r.get('census2011code'), 10))}) "
                     "ON DUPLICATE KEY UPDATE district_code = VALUES(district_code), name = VALUES(name), "
                     "census_code = VALUES(census_code)")
            n += 1
    report(f"sub-districts: {n}")
    n = 0
    for r in read_csv(find_file(lgd, "blocks")):
        if as_int(r.get("statecode")) not in states:
            continue
        code, d = as_int(r.get("developmentblockcode")), as_int(r.get("districtcode"))
        if code and d:
            sql.stmt("INSERT INTO geo_blocks (block_code, district_code, name) VALUES "
                     f"({code}, {d}, {q(clean(r.get('developmentblocknameinenglish') or r.get('developmentblockname'), 100))}) "
                     "ON DUPLICATE KEY UPDATE district_code = VALUES(district_code), name = VALUES(name)")
            n += 1
    report(f"blocks: {n}")

    # --- villages
    village_state: dict[int, int] = {}
    n = 0
    for r in read_csv(find_file(lgd, "villages")):
        st = as_int(r.get("statecode"))
        if st not in states:
            continue
        code = as_int(r.get("villagecode"))
        d, sd = as_int(r.get("districtcode")), as_int(r.get("subdistrictcode"))
        name = clean(r.get("villagenameinenglish") or r.get("villagename"), 150)
        if not (code and d and sd and name):
            continue
        village_state[code] = st
        sql.stmt("INSERT INTO geo_villages (village_code, name, name_key, state_code, district_code, "
                 "subdistrict_code, census_code) VALUES "
                 f"({code}, {q(name)}, {q(name_key(name)[:150])}, {st}, {d}, {sd}, "
                 f"{q(clean(r.get('census2011code'), 10))}) "
                 "ON DUPLICATE KEY UPDATE name = VALUES(name), name_key = VALUES(name_key), "
                 "state_code = VALUES(state_code), district_code = VALUES(district_code), "
                 "subdistrict_code = VALUES(subdistrict_code), census_code = VALUES(census_code)")
        n += 1
    report(f"villages: {n}")

    # --- village -> Gram Panchayat (gp_mapping), GP district = its villages' district
    gp_villages: dict[int, list[int]] = collections.defaultdict(list)
    gp_district: dict[int, collections.Counter] = collections.defaultdict(collections.Counter)
    for r in read_csv(find_file(lgd, "gp_mapping")):
        v, body = as_int(r.get("villagecode")), as_int(r.get("localbodycode"))
        if v in village_state and body:
            gp_villages[body].append(v)
            gp_district[body][as_int(r.get("districtcode"))] += 1

    # --- Gram Panchayats
    n = 0
    loaded_bodies: dict[int, int] = {}   # body code -> district code
    for r in read_csv(find_file(lgd, "pri_local_bodies")):
        if as_int(r.get("statecode")) not in states or r.get("localbodytypecode", "").strip() != GP_TYPE_CODE:
            continue
        code = as_int(r.get("localbodycode"))
        district = gp_district[code].most_common(1)[0][0] if code in gp_district else None
        name = clean(r.get("localbodynameinenglish") or r.get("localbodyname"), 150)
        if not (code and name):
            continue
        sql.stmt("INSERT INTO local_bodies (local_body_code, name, area_kind, body_type, state_code, district_code, "
                 "is_active) VALUES "
                 f"({code}, {q(name)}, 'RURAL', {q(GP_BODY_TYPE)}, {as_int(r.get('statecode'))}, {q(district)}, TRUE) "
                 "ON DUPLICATE KEY UPDATE name = VALUES(name), area_kind = 'RURAL', body_type = VALUES(body_type), "
                 "state_code = VALUES(state_code), district_code = COALESCE(VALUES(district_code), district_code), "
                 "is_active = TRUE")
        loaded_bodies[code] = district
        n += 1
    report(f"Gram Panchayats: {n}")

    # --- urban bodies; district = most common district of the villages / towns they cover
    ulb_cover: dict[int, list[int]] = collections.defaultdict(list)
    ulb_district: dict[int, collections.Counter] = collections.defaultdict(collections.Counter)
    for r in read_csv(find_file(lgd, "statewise_ulbs_coverage")):
        body = as_int(r.get("localbodycode"))
        if not body:
            continue
        ulb_district[body][as_int(r.get("districtcode"))] += 1
        v = as_int(r.get("villagecode"))
        if v in village_state:
            ulb_cover[body].append(v)
    n = 0
    for r in read_csv(find_file(lgd, "urban_local_bodies")):
        st = as_int(r.get("statecode"))
        if st not in states:
            continue
        code = as_int(r.get("localbodycode"))
        type_code = r.get("localbodytypecode", "").strip()
        name = clean(r.get("localbodynameinenglish") or r.get("localbodyname"), 150)
        if not (code and name):
            continue
        district = None
        if code in ulb_district:
            district = next((d for d, _ in ulb_district[code].most_common() if d), None)
        census = as_int(r.get("census2011code"))
        sql.stmt("INSERT INTO local_bodies (local_body_code, name, area_kind, body_type, state_code, district_code, "
                 "census_code, is_active) VALUES "
                 f"({code}, {q(name)}, 'URBAN', {q(URBAN_TYPE_NAMES.get(type_code, 'Urban Local Body'))}, {st}, "
                 f"{q(district)}, {q(str(census) if census else None)}, TRUE) "
                 "ON DUPLICATE KEY UPDATE name = VALUES(name), area_kind = 'URBAN', body_type = VALUES(body_type), "
                 "state_code = VALUES(state_code), district_code = COALESCE(VALUES(district_code), district_code), "
                 "census_code = COALESCE(VALUES(census_code), census_code), is_active = TRUE")
        loaded_bodies[code] = district
        n += 1
    report(f"urban local bodies: {n}")

    # bodies of these states that LGD no longer lists: kept (complaints may point to
    # them) but switched off so nobody can pick them any more
    in_states = ",".join(str(s) for s in sorted(states))
    if loaded_bodies:
        sql.commit()
        sql.raw("CREATE TEMPORARY TABLE tmp_loaded_bodies (code INT UNSIGNED PRIMARY KEY);")
        codes = sorted(loaded_bodies)
        for i in range(0, len(codes), 1000):
            sql.raw("INSERT INTO tmp_loaded_bodies VALUES " + ",".join(f"({c})" for c in codes[i:i + 1000]) + ";")
        sql.raw(f"UPDATE local_bodies lb LEFT JOIN tmp_loaded_bodies t ON t.code = lb.local_body_code "
                f"SET lb.is_active = FALSE WHERE lb.state_code IN ({in_states}) AND t.code IS NULL;")
        sql.raw("DROP TEMPORARY TABLE tmp_loaded_bodies;")

    # --- village -> body links: replaced as a whole for these states
    sql.commit()
    sql.raw(f"DELETE vlb FROM village_local_bodies vlb JOIN geo_villages v ON v.village_code = vlb.village_code "
            f"WHERE v.state_code IN ({in_states});")
    n = 0
    for body, villages in list(gp_villages.items()) + list(ulb_cover.items()):
        if body not in loaded_bodies:
            continue
        for i in range(0, len(villages), 500):
            values = ",".join(f"({v},{body})" for v in villages[i:i + 500])
            sql.stmt(f"INSERT IGNORE INTO village_local_bodies (village_code, local_body_code) VALUES {values}")
        n += len(villages)
    report(f"village -> body links: {n}")

    # --- village -> PIN (LGD), replaced as a whole for these states
    sql.commit()
    sql.raw(f"DELETE vp FROM village_pincodes vp JOIN geo_villages v ON v.village_code = vp.village_code "
            f"WHERE v.state_code IN ({in_states});")
    n = 0
    batch: list[str] = []
    for r in read_csv(find_file(lgd, "pincode_villages")):
        v, pin = as_int(r.get("villagecode")), (r.get("pincode") or "").strip()
        if v in village_state and re.fullmatch(r"[1-9][0-9]{5}", pin):
            batch.append(f"({v},'{pin}','LGD')")
            n += 1
            if len(batch) == 500:
                sql.stmt("INSERT IGNORE INTO village_pincodes (village_code, pincode, source) VALUES " + ",".join(batch))
                batch = []
    if batch:
        sql.stmt("INSERT IGNORE INTO village_pincodes (village_code, pincode, source) VALUES " + ",".join(batch))
    report(f"village -> PIN links: {n}")

    # --- PIN <-> body (many-to-many): derived from LGD village PINs + LGD urban PINs
    sql.commit()
    sql.raw(f"DELETE plb FROM pincode_local_bodies plb JOIN local_bodies lb ON lb.local_body_code = plb.local_body_code "
            f"WHERE lb.state_code IN ({in_states}) AND plb.mapping_source IN ('LGD_VILLAGE_PIN', 'LGD_URBAN_PIN');")
    sql.raw("INSERT INTO pincode_local_bodies (pincode, local_body_code, mapping_source, verification_status, "
            "village_count, import_id) "
            "SELECT vp.pincode, vlb.local_body_code, 'LGD_VILLAGE_PIN', 'PUBLISHED', COUNT(*), @imp "
            "FROM village_pincodes vp JOIN village_local_bodies vlb ON vlb.village_code = vp.village_code "
            f"JOIN geo_villages v ON v.village_code = vp.village_code WHERE v.state_code IN ({in_states}) "
            "GROUP BY vp.pincode, vlb.local_body_code;")
    n = 0
    for r in read_csv(find_file(lgd, "pincode_urban")):
        body, pin = as_int(r.get("localbodycode")), (r.get("pincode") or "").strip()
        if body in loaded_bodies and re.fullmatch(r"[1-9][0-9]{5}", pin):
            sql.stmt("INSERT INTO pincode_local_bodies (pincode, local_body_code, mapping_source, verification_status, "
                     f"village_count, import_id) VALUES ('{pin}', {body}, 'LGD_URBAN_PIN', 'PUBLISHED', 0, @imp) "
                     "ON DUPLICATE KEY UPDATE import_id = @imp")
            n += 1
    report(f"urban body -> PIN links: {n}")

    # --- wards (LGD lists: number + name, no boundary)
    seen: dict[int, set[str]] = collections.defaultdict(set)

    def ward_rows(stem: str, kind: str) -> int:
        count = 0
        for r in read_csv(find_file(lgd, stem)):
            body = as_int(r.get("localbodycode"))
            if body not in loaded_bodies:
                continue
            if kind == "RURAL":
                if r.get("localbodytype", "").strip() != "Gram Panchayat":
                    continue
                if gp_ward_districts is not None and loaded_bodies.get(body) not in gp_ward_districts:
                    continue
                wcode = as_int(r.get("wordcode") or r.get("wardcode"))
                number = clean(r.get("wordnumber") or r.get("wardnumber"), 20)
                name = clean(r.get("wardnameinenglish") or r.get("wardname"), 150)
            else:
                wcode = as_int(r.get("wardcode"))
                number = clean(r.get("wardnumber"), 20)
                name = clean(r.get("wardname"), 150)
            if not wcode:
                continue
            name = name or (f"Ward {number}" if number else None)
            if not name:
                continue
            if name_key(name) in seen[body]:
                # two wards of one body with the same name: keep both, told apart by number
                name = f"{name} (No. {number or wcode})"[:150]
            seen[body].add(name_key(name))
            sql.stmt("INSERT INTO wards (name, local_body_code, lgd_ward_code, ward_number, source, source_ref, "
                     "source_date, verification_status, is_active) VALUES "
                     f"({q(name)}, {body}, {wcode}, {q(number)}, 'LGD', {q(str(wcode))}, {q(date)}, 'PUBLISHED', TRUE) "
                     "ON DUPLICATE KEY UPDATE name = VALUES(name), local_body_code = VALUES(local_body_code), "
                     "ward_number = VALUES(ward_number), source_date = VALUES(source_date)")
            count += 1
        return count

    report(f"urban wards (LGD lists): {ward_rows('urban_local_body_wards', 'URBAN')}")
    report(f"Gram Panchayat wards (LGD lists): {ward_rows('pri_local_body_wards', 'RURAL')}")

    if serve:
        sql.commit()
        sql.raw(f"UPDATE geo_districts SET is_served = TRUE WHERE district_code IN ({','.join(map(str, sorted(serve)))});")
    sql.end_import(count=sql.total)
    sql.commit()


# ---------------------------------------------------------------------------
# India Post offices
# ---------------------------------------------------------------------------

def load_lgd_districts(lgd: Path, states: set[int]) -> tuple[dict[int, str], dict[int, dict[str, int]]]:
    state_names: dict[int, str] = {}
    for r in read_csv(find_file(lgd, "states")):
        code = as_int(r.get("statecode"))
        if code in states:
            state_names[code] = r.get("statenameinenglish") or r.get("statename") or ""
    districts: dict[int, dict[str, int]] = collections.defaultdict(dict)
    for r in read_csv(find_file(lgd, "districts")):
        st = as_int(r.get("statecode"))
        if st in states:
            districts[st][name_key(r.get("districtnameinenglish") or r.get("districtname"))] = as_int(r.get("districtcode"))
    return state_names, districts


# India Post still uses some old district names; LGD has the new ones. Only
# renames published by the state government are listed here.
DISTRICT_RENAMES = {
    "allahabad": "prayagraj", "faizabad": "ayodhya", "kansiramnagar": "kasganj",
    "santravidasnagar": "bhadohi", "santravidasnagarbhadohi": "bhadohi", "jyotibaphulenagar": "amroha",
    "mahamayanagar": "hathras", "prabudhnagar": "shamli", "panchsheelnagar": "hapur",
    "bhimnagar": "sambhal", "lakhimpurkheri": "kheri", "sravasti": "shrawasti", "shravasti": "shrawasti",
    "maharajganj": "mahrajganj", "santkabeernagar": "santkabirnagar",
    "kaimur": "kaimurbhabua", "eastchamparan": "purbichamparan", "purbachamparan": "purbichamparan",
    "westchamparan": "pashchimchamparan",
}


def cmd_postoffices(args: argparse.Namespace) -> None:
    states = parse_states(args.states)
    state_names, districts = load_lgd_districts(Path(args.lgd_dir).expanduser(), states)
    by_state_name = {name_key(n): code for code, n in state_names.items()}
    sql = Sql()
    sql.begin_import("POST_OFFICES", Path(args.csv).name, args.source_date, "states " + args.states)
    n = 0
    unmatched: collections.Counter = collections.Counter()
    for r in read_csv(Path(args.csv).expanduser()):
        st = by_state_name.get(name_key(r.get("statename")))
        if st is None:
            continue
        pin = (r.get("pincode") or "").strip()
        office = clean(r.get("officename"), 150)
        if not (re.fullmatch(r"[1-9][0-9]{5}", pin) and office):
            continue
        dkey = name_key(r.get("district"))
        dkey = DISTRICT_RENAMES.get(dkey, dkey)
        district = districts[st].get(dkey)
        if district is None:
            unmatched[(r.get("district"), state_names[st])] += 1
        lat, lng = coord(r.get("latitude"), 6, 38), coord(r.get("longitude"), 68, 98)
        if lat is None or lng is None:
            lat = lng = None
        sql.stmt("INSERT INTO pincode_offices (pincode, office_name, office_type, delivery, division_name, district_code, "
                 "district_name, state_name, latitude, longitude, source, source_date) VALUES "
                 f"('{pin}', {q(office)}, {q(clean(r.get('officetype'), 10))}, {q(clean(r.get('delivery'), 20))}, "
                 f"{q(clean(r.get('divisionname'), 100))}, {q(district)}, {q(clean(r.get('district'), 100))}, "
                 f"{q(clean(r.get('statename'), 100))}, {q(lat)}, {q(lng)}, 'INDIA_POST', {q(args.source_date)}) "
                 "ON DUPLICATE KEY UPDATE office_type = VALUES(office_type), delivery = VALUES(delivery), "
                 "division_name = VALUES(division_name), district_code = VALUES(district_code), "
                 "district_name = VALUES(district_name), state_name = VALUES(state_name), "
                 "latitude = VALUES(latitude), longitude = VALUES(longitude), source = VALUES(source), "
                 "source_date = VALUES(source_date)")
        n += 1
    sql.end_import(count=n)
    report(f"post offices: {n}")
    for (d, s), c in sorted(unmatched.items()):
        report(f"  district not in LGD, stored without district_code: {d} ({s}) - {c} offices")


def coord(value, low: float, high: float) -> float | None:
    """India Post lat/long; values outside India's box (typos, swapped) are dropped."""
    try:
        v = round(float(str(value).strip()), 6)
    except (TypeError, ValueError):
        return None
    return v if low <= v <= high else None


# ---------------------------------------------------------------------------
# Boundaries
# ---------------------------------------------------------------------------

def clean_geometry(geom: dict) -> dict | None:
    """Closes rings and drops rings with fewer than 4 points. Coordinates stay
    exactly as published (lon, lat). Returns None when nothing usable is left."""
    if not geom or geom.get("type") not in ("Polygon", "MultiPolygon"):
        return None

    def ring_ok(ring):
        pts = [[round(float(p[0]), 7), round(float(p[1]), 7)] for p in ring if len(p) >= 2]
        if pts and pts[0] != pts[-1]:
            pts.append(pts[0])
        return pts if len(pts) >= 4 else None

    def poly_ok(poly):
        rings = [r for r in (ring_ok(r) for r in poly) if r]
        return rings if rings and poly and ring_ok(poly[0]) else None

    if geom["type"] == "Polygon":
        p = poly_ok(geom["coordinates"])
        return {"type": "Polygon", "coordinates": p} if p else None
    polys = [p for p in (poly_ok(p) for p in geom["coordinates"]) if p]
    if not polys:
        return None
    return {"type": "Polygon", "coordinates": polys[0]} if len(polys) == 1 else \
        {"type": "MultiPolygon", "coordinates": polys}


def merge_geometries(geoms: list[dict]) -> dict:
    polys = []
    for g in geoms:
        polys.extend([g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"])
    return {"type": "Polygon", "coordinates": polys[0]} if len(polys) == 1 else \
        {"type": "MultiPolygon", "coordinates": polys}


def boundary_stmt(layer: str, ref_code_sql: str, name: str | None, geom: dict, source: str,
                  source_ref: str, source_date: str | None, status: str = "PUBLISHED") -> str:
    """INSERT that keeps only geometries MySQL accepts as valid (ST_IsValid) -
    an invalid polygon would make ST_Contains fail later; skipped ones are
    counted by the import summary."""
    gj = json.dumps(geom, separators=(",", ":"))
    return ("INSERT INTO geo_boundaries (layer, ref_code, name, geom, source, source_ref, source_date, "
            "verification_status, import_id) "
            f"SELECT {q(layer)}, r.ref, {q(clean(name, 200))}, t.g, {q(source)}, {q(source_ref)}, {q(source_date)}, "
            f"{q(status)}, @imp "
            f"FROM (SELECT ST_GeomFromGeoJSON({q(gj)}, 1, 4326) AS g) t, (SELECT {ref_code_sql} AS ref) r "
            "WHERE r.ref IS NOT NULL AND ST_IsValid(t.g) "
            "ON DUPLICATE KEY UPDATE ref_code = VALUES(ref_code), name = VALUES(name), geom = VALUES(geom), "
            "source_date = VALUES(source_date), import_id = VALUES(import_id)")


def ulb_matcher(lgd: Path, states: set[int]):
    """SBM town (Census 2011 code + name + district) -> (LGD urban body code, how).

    how = CENSUS        same Census 2011 town code;
          NAME_DISTRICT exact normalised name among the LGD urban bodies of the
                        same district (towns created after 2011 have no census code);
          NAME_STATE    LGD has no district for the body (no coverage rows) but
                        exactly one urban body of the state has this name - the
                        rows built from it are stored as UNVERIFIED.
    Anything else is not linked (None). Returns (match, list of name links)."""
    _, districts = load_lgd_districts(lgd, states)
    by_census: dict[str, int] = {}
    names: dict[int, str] = {}
    types: dict[int, str] = {}
    by_state: dict[int, dict[str, list[int]]] = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in read_csv(find_file(lgd, "urban_local_bodies")):
        st = as_int(r.get("statecode"))
        if st not in states:
            continue
        code = as_int(r.get("localbodycode"))
        census = as_int(r.get("census2011code"))
        names[code] = r.get("localbodynameinenglish") or r.get("localbodyname") or ""
        types[code] = (r.get("localbodytypecode") or "").strip()
        by_state[st][town_key(names[code])].append(code)
        if census:
            by_census[str(census)] = code
    by_district: dict[int, dict[str, list[int]]] = collections.defaultdict(lambda: collections.defaultdict(list))
    has_district: set[int] = set()
    for r in read_csv(find_file(lgd, "statewise_ulbs_coverage")):
        code = as_int(r.get("localbodycode"))
        if code in names:
            d = as_int(r.get("districtcode"))
            has_district.add(code)
            key = town_key(names[code])
            if code not in by_district[d][key]:
                by_district[d][key].append(code)
    by_name_links: list[str] = []
    seen_links: set[str] = set()

    def link(text: str) -> None:
        if text not in seen_links:
            seen_links.add(text)
            by_name_links.append(text)

    def same_type(code: int, ulb_name: str) -> bool:
        """When the source name says the type (NPP / NP / Nagar Nigam) it must be LGD's type."""
        t = written_type(ulb_name)
        return t is None or t == types.get(code)

    def match(census_code: str, ulb_name: str, district_name: str, state: int) -> tuple[int, str] | None:
        if census_code and census_code.strip() in by_census:
            return by_census[census_code.strip()], "CENSUS"
        key = town_key(ulb_name)
        if not key:
            return None
        d = districts[state].get(DISTRICT_RENAMES.get(name_key(district_name), name_key(district_name)))
        if d is not None:
            hits = by_district[d].get(key, [])
            if len(hits) == 1 and same_type(hits[0], ulb_name):
                link(f"{ulb_name.strip()} ({district_name.strip()}) -> {names[hits[0]]} [LGD {hits[0]}] same district")
                return hits[0], "NAME_DISTRICT"
        hits = [c for c in by_state[state].get(key, []) if c not in has_district]
        if len(by_state[state].get(key, [])) == 1 and len(hits) == 1 and same_type(hits[0], ulb_name):
            link(f"{ulb_name.strip()} ({district_name.strip()}) -> {names[hits[0]]} [LGD {hits[0]}] "
                 "only body of that name in the state, LGD gives no district - UNVERIFIED")
            return hits[0], "NAME_STATE"
        return None

    return match, by_name_links


def ward_number(code, name: str | None) -> str | None:
    """SBM ward number: the wardcode field, else the number written in the
    ward name ('Ward 35', 'Ward No. 7') - read from the source, never made up."""
    number = clean(str(code or ""), 20)
    if number:
        return number
    m = re.search(r"(?i)\bward\s*(?:no\.?\s*)?(\d{1,4})\b", name or "")
    return m.group(1) if m else None


def cmd_boundaries(args: argparse.Namespace) -> None:
    states = parse_states(args.states)
    path = Path(args.file).expanduser()
    layer = args.layer
    date = args.source_date
    sql = Sql()
    lgd = Path(args.lgd_dir).expanduser()
    sql.begin_import("BOUNDARY_" + layer, path.name, date, "states " + args.states)
    skipped: collections.Counter = collections.Counter()
    written = 0

    if layer in ("WARD", "LOCAL_BODY"):
        match, by_name = ulb_matcher(lgd, states)
        state_codes = {str(s) for s in states}
        if layer == "WARD":
            groups: dict[tuple[int, str], dict] = {}
            for f in geojson_lines(path):
                p = f.get("properties") or {}
                if str(p.get("statecode", "")).strip() not in state_codes:
                    continue
                st = int(str(p["statecode"]).strip())
                hit = match(str(p.get("ulbcode") or ""), p.get("ulbname") or "", p.get("districtname") or "", st)
                if hit is None:
                    skipped[f"town not found in LGD: {p.get('ulbname')} ({p.get('districtname')})"] += 1
                    continue
                body, how = hit
                number = ward_number(p.get("wardcode"), p.get("wardname"))
                geom = clean_geometry(f.get("geometry"))
                if not number or geom is None:
                    skipped["ward without number or usable polygon"] += 1
                    continue
                key = (body, number)
                g = groups.setdefault(key, {"names": [], "geoms": [], "ulb": p.get("ulbcode"), "ids": [],
                                            "status": "UNVERIFIED" if how == "NAME_STATE" else "PUBLISHED"})
                g["names"].append(clean(p.get("wardname"), 150) or "")
                g["geoms"].append(geom)
                g["ids"].append(str(p.get("objectid")))
            bodies_with_sbm = sorted({b for b, _ in groups})
            for (body, number), g in sorted(groups.items()):
                name = ward_display_name(number, g["names"][0])
                ref = f"{g['ulb']}:{number}"
                sql.stmt("INSERT INTO wards (name, local_body_code, ward_number, source, source_ref, source_date, "
                         "verification_status, is_active) VALUES "
                         f"({q(name)}, {body}, {q(number)}, 'SBM', {q(ref)}, {q(date)}, {q(g['status'])}, TRUE) "
                         "ON DUPLICATE KEY UPDATE name = VALUES(name), local_body_code = VALUES(local_body_code), "
                         "ward_number = VALUES(ward_number), source_date = VALUES(source_date), is_active = TRUE, "
                         "verification_status = IF(verification_status = 'VERIFIED', 'VERIFIED', VALUES(verification_status))")
                sql.stmt(boundary_stmt("WARD", f"(SELECT CAST(ward_id AS CHAR) FROM wards WHERE source = 'SBM' "
                                               f"AND source_ref = {q(ref)})",
                                       name, merge_geometries(g["geoms"]), "SBM", ref, date, g["status"]))
                written += 1
            # One ward list per body: where SBM maps the wards, the LGD list
            # (often an older delimitation with different numbers) is switched off.
            sql.commit()
            for i in range(0, len(bodies_with_sbm), 500):
                codes = ",".join(map(str, bodies_with_sbm[i:i + 500]))
                sql.raw(f"UPDATE wards SET is_active = FALSE WHERE source = 'LGD' AND local_body_code IN ({codes});")
            report(f"towns with SBM ward maps: {len(bodies_with_sbm)} (their LGD ward lists switched off)")
        else:
            for f in geojson_lines(path):
                p = f.get("properties") or {}
                if str(p.get("stcode", "")).strip() not in state_codes:
                    continue
                st = int(str(p["stcode"]).strip())
                hit = match(str(p.get("ulbcode") or ""), p.get("ulbnm") or "", p.get("distnm") or "", st)
                geom = clean_geometry(f.get("geometry"))
                if hit is None or geom is None:
                    skipped[f"town not found in LGD or no polygon: {p.get('ulbnm')} ({p.get('distnm')})"] += 1
                    continue
                body, how = hit
                sql.stmt(boundary_stmt("LOCAL_BODY", q(str(body)), p.get("ulbnm"), geom, "SBM",
                                       str(p.get("objectid")), date,
                                       "UNVERIFIED" if how == "NAME_STATE" else "PUBLISHED"))
                written += 1
        for line in by_name:
            report(f"  linked by exact name (no Census 2011 code): {line}")

    elif layer == "VILLAGE":
        valid = lgd_codes(lgd, "villages", "villagecode", states)
        for f in geojson_lines(path):
            p = f.get("properties") or {}
            if as_int(p.get("state_lgd")) not in states:
                continue
            code = as_int(p.get("vil_lgd"))
            geom = clean_geometry(f.get("geometry"))
            if code not in valid or geom is None:
                skipped["village code not in LGD list or no polygon"] += 1
                continue
            sql.stmt(boundary_stmt("VILLAGE", q(str(code)), p.get("vilname11") or p.get("vilnam_soi"), geom,
                                   "BHARATMAPS_LGD", str(p.get("OBJECTID")), date))
            written += 1

    elif layer == "PANCHAYAT":
        valid = lgd_codes(lgd, "pri_local_bodies", "localbodycode", states)
        for f in geojson_lines(path):
            p = f.get("properties") or {}
            if as_int(p.get("st_lgd")) not in states:
                continue
            code = as_int(p.get("gp_code") or p.get("gpcode"))
            geom = clean_geometry(f.get("geometry"))
            if code not in valid or geom is None:
                skipped["Gram Panchayat code not in LGD list or no polygon"] += 1
                continue
            sql.stmt(boundary_stmt("PANCHAYAT", q(str(code)), p.get("gp_name") or p.get("gpname"), geom,
                                   "BHARATMAPS_LGD", str(p.get("OBJECTID")), date))
            written += 1

    elif layer == "PINCODE":
        state_names, _ = load_lgd_districts(lgd, states)
        wanted = {name_key(n) for n in state_names.values()}
        for i, f in enumerate(geojson_lines(path)):
            p = f.get("properties") or {}
            if name_key(p.get("Circle")) not in wanted:
                continue
            pin = str(p.get("Pincode") or "").strip()
            geom = clean_geometry(f.get("geometry"))
            if not re.fullmatch(r"[1-9][0-9]{5}", pin) or geom is None:
                skipped["PIN polygon without a valid PIN"] += 1
                continue
            sql.stmt(boundary_stmt("PINCODE", q(pin), p.get("Office_Name"), geom, "DATAGOV_PIN",
                                   f"{pin}:{i}", date))
            written += 1
    else:
        raise SystemExit("unknown --layer")

    sql.end_import(count_sql="SELECT COUNT(*) FROM geo_boundaries WHERE import_id = @imp")
    sql.raw(f"SELECT '{layer}' AS layer, {written} AS sent, "
            "(SELECT COUNT(*) FROM geo_boundaries WHERE import_id = @imp) AS stored_valid;")
    report(f"{layer} polygons sent: {written} (rows MySQL rejects as invalid geometry are skipped; "
           "the 'stored_valid' line printed by mysql shows how many were stored)")
    for why, c in skipped.most_common(40):
        report(f"  skipped {c}: {why}")


def ward_display_name(number: str, source_name: str) -> str:
    """'Bagu Christian Nagar 1' + number 1 -> 'Ward 1 - Bagu Christian Nagar'."""
    n = (source_name or "").strip()
    n = re.sub(rf"[\s\-]*\(?\b(ward\s*(no\.?)?\s*)?{re.escape(number)}\)?\s*$", "", n, flags=re.I).strip(" -")
    if not n or re.fullmatch(r"ward(\s*no\.?)?", n, re.I):
        return f"Ward {number}"
    return f"Ward {number} - {n}"[:150]


def lgd_codes(lgd: Path, stem: str, column: str, states: set[int]) -> set[int]:
    return {as_int(r.get(column)) for r in read_csv(find_file(lgd, stem)) if as_int(r.get("statecode")) in states}


# ---------------------------------------------------------------------------
# Centres
# ---------------------------------------------------------------------------

def cmd_centres(args: argparse.Namespace) -> None:
    """Map centre of each village / ward = centre of its largest polygon. ST_Centroid
    is computed on the cartesian copy (SRID 0, x = longitude) - fine for centring a map."""
    sql = Sql()
    sql.raw("SET NAMES utf8mb4;")
    sql.raw("""
UPDATE geo_villages v
  JOIN (SELECT b.ref_code, ST_Centroid(ST_SRID(b.geom, 0)) AS c
          FROM geo_boundaries b
          JOIN (SELECT ref_code, MAX(ST_Area(ST_SRID(geom, 0))) AS a FROM geo_boundaries
                 WHERE layer = 'VILLAGE' GROUP BY ref_code) m
            ON m.ref_code = b.ref_code AND ST_Area(ST_SRID(b.geom, 0)) = m.a
         WHERE b.layer = 'VILLAGE') x ON x.ref_code = CAST(v.village_code AS CHAR)
   SET v.centre_latitude = ROUND(ST_Y(x.c), 6), v.centre_longitude = ROUND(ST_X(x.c), 6)
 WHERE v.centre_latitude IS NULL OR %s;""" % ("TRUE" if args.overwrite else "FALSE"))
    sql.raw("""
UPDATE wards w
  JOIN geo_boundaries b ON b.layer = 'WARD' AND b.ref_code = CAST(w.ward_id AS CHAR)
   SET w.centre_latitude = ROUND(ST_Y(ST_Centroid(ST_SRID(b.geom, 0))), 6),
       w.centre_longitude = ROUND(ST_X(ST_Centroid(ST_SRID(b.geom, 0))), 6)
 WHERE w.centre_latitude IS NULL OR %s;""" % ("TRUE" if args.overwrite else "FALSE"))
    sql.raw("SELECT (SELECT COUNT(*) FROM geo_villages WHERE centre_latitude IS NOT NULL) AS villages_with_centre, "
            "(SELECT COUNT(*) FROM wards WHERE centre_latitude IS NOT NULL) AS wards_with_centre;")


# ---------------------------------------------------------------------------

def main(argv: Iterable[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("lgd", help="LGD daily dump CSVs")
    p.add_argument("--dir", required=True, help="folder with the unpacked LGD CSVs (villages.<date>.csv ...)")
    p.add_argument("--states", required=True, help="LGD state codes, e.g. 9,10 (Uttar Pradesh, Bihar)")
    p.add_argument("--source-date", help="download date YYYY-MM-DD")
    p.add_argument("--serve", default="", help="LGD district codes this deployment serves, e.g. 145,196")
    p.add_argument("--gp-wards", default="served",
                   help="Gram Panchayat ward lists to load: 'served' (default: districts in --serve and "
                        "--served-before), 'all', 'none' or district codes")
    p.add_argument("--served-before", default="145,196",
                   help="districts already served (default: the pilot districts 145 Ghaziabad, 196 Gaya)")
    p.set_defaults(func=cmd_lgd)

    p = sub.add_parser("postoffices", help="India Post office directory CSV")
    p.add_argument("--csv", required=True)
    p.add_argument("--lgd-dir", required=True, help="LGD folder (district names)")
    p.add_argument("--states", required=True)
    p.add_argument("--source-date")
    p.set_defaults(func=cmd_postoffices)

    p = sub.add_parser("boundaries", help="polygons from .geojsonl / .geojsonl.7z")
    p.add_argument("--layer", required=True, choices=["WARD", "LOCAL_BODY", "VILLAGE", "PANCHAYAT", "PINCODE"])
    p.add_argument("--file", required=True)
    p.add_argument("--lgd-dir", required=True, help="LGD folder (codes to check against)")
    p.add_argument("--states", required=True)
    p.add_argument("--source-date")
    p.set_defaults(func=cmd_boundaries)

    p = sub.add_parser("centres", help="village / ward map centres from the polygons")
    p.add_argument("--overwrite", action="store_true", help="also replace centres set before (e.g. by an Admin)")
    p.set_defaults(func=cmd_centres)

    args = ap.parse_args(list(argv) if argv is not None else None)
    if getattr(args, "source_date", None):
        dt.date.fromisoformat(args.source_date)
    args.func(args)


if __name__ == "__main__":
    main()
