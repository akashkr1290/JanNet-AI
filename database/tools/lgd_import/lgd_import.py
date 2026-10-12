#!/usr/bin/env python3
"""Turns Local Government Directory (LGD) and India Post CSV downloads into
SQL that loads the V34 location tables (geo_*, local_bodies,
village_local_bodies, wards, village_pincodes, pincode_offices).

Where the files come from (all free, Government Open Data License - India):
  * https://lgdirectory.gov.in -> Download Directory (CSV):
      states, districts, sub-districts, blocks, "villages with local body
      mapping" (village directory), urban local bodies, rural (PRI) local
      bodies, "wards of urban/rural local bodies".
  * data.gov.in -> "All India Pincode Directory" (India Post offices) and
    the LGD "villages with PIN code" list.

Output is plain SQL (INSERT ... ON DUPLICATE KEY UPDATE), so a re-run with a
newer download updates names in place: LGD codes are the primary keys and
never change. Apply it with any MySQL client - see README.md next to this file.

Only the standard library is used, so it runs anywhere Python 3.9+ exists.
Headers are matched loosely (case, spaces, "(In English)" ignored) because
LGD has renamed columns between releases.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import io
import re
import sys
import unicodedata
import zipfile
from pathlib import Path

BATCH = 500

# LGD urban local body type codes (local-body-type-code list); anything not
# listed here is a rural Panchayati Raj body.
URBAN_TYPE_CODES = {"4", "5", "6", "7", "8", "21", "24", "25"}
URBAN_TYPE_NAMES = {
    "4": "Municipal Corporation", "5": "Municipality", "6": "Notified Area Council",
    "7": "Town Panchayat", "8": "Municipal Council", "21": "NCT Municipal Council",
    "24": "City Municipal Council", "25": "Town Municipal Council",
}


# Rural body codes created by load_villages; load_rural_body_types only
# touches these (the PRI list covers all of India).
LOADED_RURAL_BODIES: set[int] = set()

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def name_key(name: str) -> str:
    """Same rule as backend GeoNames.normalize(): lower-case ASCII letters and
    digits only. Stored in geo_villages.name_key / ward_localities.locality_key."""
    text = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]", "", text.lower())


def norm_header(h: str) -> str:
    h = re.sub(r"\(.*?\)", "", h or "")          # "Village Name(In English)" -> "Village Name"
    return re.sub(r"[^a-z0-9]", "", h.lower())


def open_csv(path: Path):
    """Yields dict rows keyed by normalised header. Reads .csv or a .zip holding one .csv."""
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as z:
            name = next(n for n in z.namelist() if n.lower().endswith(".csv") and not n.startswith("__MACOSX"))
            raw = z.read(name).decode("utf-8-sig", errors="replace")
    else:
        raw = path.read_text(encoding="utf-8-sig", errors="replace")
    reader = csv.reader(io.StringIO(raw))
    header = next(reader)
    keys = []
    for h in header:                               # LGD repeats some headers (English + local name)
        k = norm_header(h)
        keys.append(k if k not in keys else k + "_2")
    for row in reader:
        if not any(c.strip() for c in row):
            continue
        yield {k: (row[i].strip() if i < len(row) else "") for i, k in enumerate(keys)}


def col(row: dict, *names: str, required: bool = True) -> str:
    """First non-empty value among the given (normalised) header names."""
    for n in names:
        v = row.get(n)
        if v not in (None, ""):
            return v
    if required and not any(n in row for n in names):
        raise KeyError(f"none of the columns {names} found; header has {sorted(row)}")
    return ""


def num(value: str) -> int | None:
    value = (value or "").replace(",", "").replace('"', "").strip()
    return int(value) if value.isdigit() and int(value) > 0 else None


def sql_str(value) -> str:
    if value is None or value == "":
        return "NULL"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def title(name: str) -> str:
    """LGD writes many names in CAPITALS; show them as 'Ghaziabad', keep mixed case as is."""
    return name.title() if name.isupper() else name


class SqlOut:
    def __init__(self, fh):
        self.fh = fh

    def upsert(self, table: str, columns: list[str], rows: list[tuple], update: list[str]):
        if not rows:
            return
        upd = ", ".join(f"{c} = VALUES({c})" for c in update) or f"{columns[0]} = {columns[0]}"
        for i in range(0, len(rows), BATCH):
            chunk = rows[i:i + BATCH]
            values = ",\n  ".join("(" + ", ".join(sql_str(v) for v in r) + ")" for r in chunk)
            self.fh.write(f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n  {values}\n"
                          f"ON DUPLICATE KEY UPDATE {upd};\n")

    def line(self, text: str):
        self.fh.write(text + "\n")


# ---------------------------------------------------------------------------
# importers - each reads one LGD / India Post file kind
# ---------------------------------------------------------------------------

def load_states(path, out):
    rows = []
    for r in open_csv(path):
        code = num(col(r, "statecode"))
        if code:
            rows.append((code, title(col(r, "statename")), col(r, "census2011code", required=False) or None))
    out.upsert("geo_states", ["state_code", "name", "census_code"], rows, ["name", "census_code"])
    return len(rows)


def load_districts(path, out, states: set[int] | None):
    rows = []
    for r in open_csv(path):
        code, state = num(col(r, "districtcode")), num(col(r, "statecode"))
        if code and state and (not states or state in states):
            rows.append((code, state, title(col(r, "districtname")), col(r, "census2011code", required=False) or None))
    out.upsert("geo_districts", ["district_code", "state_code", "name", "census_code"], rows,
               ["state_code", "name", "census_code"])   # is_served is never touched by an import
    return len(rows)


def load_villages(path, out, districts: set[int]):
    """LGD village directory: one row per village + its sub-district, block and
    local body. Also creates the sub-districts, blocks and rural local bodies
    it mentions, so the hierarchy is complete for the chosen districts."""
    subdistricts, blocks, bodies, villages, links = {}, {}, {}, {}, set()
    block_votes: dict[int, dict[int, int]] = {}
    for r in open_csv(path):
        district = num(col(r, "districtcode"))
        if district not in districts:
            continue
        state = num(col(r, "statecode"))
        sub = num(col(r, "subdistrictcode"))
        village = num(col(r, "villagecode"))
        if not (state and sub and village):
            continue
        subdistricts[sub] = (sub, district, title(col(r, "subdistrictname")), col(r, "subdistrictcensuscode", required=False) or None)
        block = num(col(r, "blockcode", required=False))
        if block:
            blocks[block] = (block, district, title(col(r, "blockname", required=False)))
        name = col(r, "villagename")
        villages[village] = (village, name, name_key(name), state, district, sub, block,
                             col(r, "villagecunseccode", "villagecensuscode", required=False) or None)
        body = num(col(r, "localbodycode", required=False))
        if body:
            bodies.setdefault(body, [title(col(r, "localbodyname", required=False)), state, district])
            links.add((village, body))
            if block:
                block_votes.setdefault(body, {}).setdefault(block, 0)
                block_votes[body][block] += 1
    out.upsert("geo_subdistricts", ["subdistrict_code", "district_code", "name", "census_code"],
               list(subdistricts.values()), ["district_code", "name", "census_code"])
    out.upsert("geo_blocks", ["block_code", "district_code", "name"], list(blocks.values()),
               ["district_code", "name"])
    body_rows = []
    for code, (name, state, district) in bodies.items():
        votes = block_votes.get(code, {})
        block = max(votes, key=votes.get) if votes else None   # the block most of its villages are in
        body_rows.append((code, name, "RURAL", "Gram Panchayat", state, district, block))
    # A body already loaded as URBAN (from the urban list) keeps its kind/type.
    out.upsert("local_bodies", ["local_body_code", "name", "area_kind", "body_type", "state_code",
                                "district_code", "block_code"], body_rows,
               ["name", "district_code", "block_code"])
    out.upsert("geo_villages", ["village_code", "name", "name_key", "state_code", "district_code",
                                "subdistrict_code", "block_code", "census_code"], list(villages.values()),
               ["name", "name_key", "state_code", "district_code", "subdistrict_code", "block_code", "census_code"])
    out.upsert("village_local_bodies", ["village_code", "local_body_code"], sorted(links), [])
    LOADED_RURAL_BODIES.update(bodies)
    return len(villages)


def read_urban_bodies(urban_path, coverage_path, districts: set[int]):
    """Urban local bodies of the chosen districts, plus the villages each one
    covers. LGD's urban list has no district column, so the municipal
    directory (body -> district / village coverage) decides which bodies
    belong to the chosen districts. Returns (body rows, village links)."""
    body_district: dict[int, int] = {}
    links = set()
    for r in open_csv(coverage_path):
        body, district = num(col(r, "localbodycode")), num(col(r, "districtcode"))
        if body and district in districts:
            body_district.setdefault(body, district)
            village = num(col(r, "villagecode", required=False))
            if village:
                links.add((village, body))
    rows = []
    for r in open_csv(urban_path):
        code = num(col(r, "localbodycode"))
        if code not in body_district:
            continue
        type_code = col(r, "localbodytypecode", required=False).replace('"', "")
        rows.append((code, title(col(r, "localbodyname")), "URBAN",
                     URBAN_TYPE_NAMES.get(type_code, "Urban Local Body"), num(col(r, "statecode")),
                     body_district[code], col(r, "census2011code", required=False) or None))
    return rows, sorted(links)


def write_urban_bodies(rows, out):
    out.upsert("local_bodies", ["local_body_code", "name", "area_kind", "body_type", "state_code",
                                "district_code", "census_code"], rows,
               ["name", "area_kind", "body_type", "district_code", "census_code"])
    return len(rows)


def write_urban_coverage(links, out):
    """Villages absorbed into a municipal body. Guarded by EXISTS because the
    municipal directory can name villages outside the loaded set."""
    for v, b in links:
        out.line(f"INSERT IGNORE INTO village_local_bodies (village_code, local_body_code) "
                 f"SELECT {v}, {b} FROM geo_villages WHERE village_code = {v};")
    return len(links)


def load_rural_body_types(path, out):
    """Sets body_type ("Village Panchayat", "Gram Panchayat" ...) from LGD's PRI
    list for the bodies load_villages created."""
    count = 0
    for r in open_csv(path):
        code = num(col(r, "localbodycode"))
        type_name = col(r, "localbodytypename", required=False)
        if code in LOADED_RURAL_BODIES and type_name:
            out.line(f"UPDATE local_bodies SET body_type = {sql_str(type_name)} "
                     f"WHERE local_body_code = {code} AND area_kind = 'RURAL';")
            count += 1
    return count


def load_wards(path, out, body_filter_sql: str):
    """LGD "wards of urban/rural local bodies". Ward rows whose body is not
    loaded are skipped by the INSERT ... SELECT (FK safety)."""
    count = 0
    for r in open_csv(path):
        body = num(col(r, "localbodycode"))
        ward_code = num(col(r, "wardcode"))
        if not (body and ward_code):
            continue
        number = col(r, "wardnumber", "wardno", required=False)
        name = col(r, "wardname", required=False) or f"Ward No. {number}"
        out.line("INSERT INTO wards (name, code, local_body_code, lgd_ward_code, ward_number, is_active) "
                 f"SELECT {sql_str(name[:150])}, {sql_str('LGD' + str(ward_code))}, {body}, {ward_code}, "
                 f"{sql_str(number[:20] if number else None)}, TRUE FROM local_bodies WHERE local_body_code = {body} "
                 f"{body_filter_sql}"
                 "ON DUPLICATE KEY UPDATE name = VALUES(name), ward_number = VALUES(ward_number), "
                 "local_body_code = VALUES(local_body_code);")
        count += 1
    return count


def load_pincode_offices(path, out, states_by_name: dict[str, int] | None):
    """India Post "All India Pincode Directory". district_code is matched to
    LGD by district name inside the state (NULL when the names differ)."""
    rows = []
    for r in open_csv(path):
        pin = col(r, "pincode")
        if not re.fullmatch(r"[1-9][0-9]{5}", pin):
            continue
        state = col(r, "statename")
        if states_by_name is not None and name_key(state) not in states_by_name:
            continue
        lat = col(r, "latitude", required=False)
        lng = col(r, "longitude", required=False)
        try:
            lat_f, lng_f = float(lat), float(lng)
            ok = 6 <= lat_f <= 38 and 68 <= lng_f <= 98      # India; India Post has some junk values
        except ValueError:
            ok = False
        rows.append((pin, title(col(r, "officename")[:150]), col(r, "officetype", required=False)[:10] or None,
                     title(col(r, "district", "districtname")), title(state),
                     round(lat_f, 6) if ok else None, round(lng_f, 6) if ok else None))
    out.upsert("pincode_offices", ["pincode", "office_name", "office_type", "district_name", "state_name",
                                   "latitude", "longitude"], rows,
               ["office_type", "district_name", "state_name", "latitude", "longitude"])
    # match India Post district names to LGD districts (same state, same name key)
    out.line("UPDATE pincode_offices p JOIN geo_states s ON LOWER(REGEXP_REPLACE(s.name, '[^A-Za-z0-9]', '')) = "
             "LOWER(REGEXP_REPLACE(p.state_name, '[^A-Za-z0-9]', '')) "
             "JOIN geo_districts d ON d.state_code = s.state_code AND LOWER(REGEXP_REPLACE(d.name, '[^A-Za-z0-9]', '')) = "
             "LOWER(REGEXP_REPLACE(p.district_name, '[^A-Za-z0-9]', '')) "
             "SET p.district_code = d.district_code WHERE p.district_code IS NULL;")
    return len(rows)


def load_village_pincodes(path, out):
    count = 0
    for r in open_csv(path):
        village = num(col(r, "villagecode"))
        pin = col(r, "pincode")
        if village and re.fullmatch(r"[1-9][0-9]{5}", pin):
            out.line(f"INSERT IGNORE INTO village_pincodes (village_code, pincode) "
                     f"SELECT {village}, '{pin}' FROM geo_villages WHERE village_code = {village};")
            count += 1
    return count


# ---------------------------------------------------------------------------

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--districts", required=True,
                   help="comma-separated LGD district codes to load villages/bodies for, e.g. 196,145")
    p.add_argument("--states-csv", type=Path, help="LGD states list")
    p.add_argument("--districts-csv", type=Path, help="LGD districts list (all of India is fine)")
    p.add_argument("--villages-csv", type=Path, action="append", default=[],
                   help="LGD village directory with local body mapping (.csv or .zip; repeatable, e.g. one per state)")
    p.add_argument("--urban-bodies-csv", type=Path, help="LGD urban local bodies list")
    p.add_argument("--municipal-coverage-csv", type=Path, help="LGD municipal directory (urban body -> district/villages)")
    p.add_argument("--rural-bodies-csv", type=Path, help="LGD PRI local bodies list (sets body type names)")
    p.add_argument("--wards-csv", type=Path, action="append", default=[], help="LGD wards file (repeatable)")
    p.add_argument("--pincode-csv", type=Path, help="India Post All India Pincode Directory")
    p.add_argument("--village-pincode-csv", type=Path, help="villages with PIN code (village code, pincode)")
    p.add_argument("--serve", action="store_true", help="mark the chosen districts as served (is_served = TRUE)")
    p.add_argument("--source-date", help="date the files were downloaded, YYYY-MM-DD")
    p.add_argument("--flyway", action="store_true",
                   help="write a Flyway migration body (no SET NAMES / transaction lines - Flyway adds its own)")
    p.add_argument("-o", "--output", type=Path, required=True, help="SQL file to write")
    a = p.parse_args(argv)

    districts = {int(x) for x in a.districts.split(",") if x.strip()}
    source_date = a.source_date or dt.date.today().isoformat()
    with a.output.open("w", encoding="utf-8") as fh:
        out = SqlOut(fh)
        out.line(f"-- Generated by database/tools/lgd_import/lgd_import.py on {dt.date.today().isoformat()}")
        out.line(f"-- Districts: {sorted(districts)}; source date {source_date}")
        if not a.flyway:
            out.line("SET NAMES utf8mb4;")
            out.line("START TRANSACTION;")

        def record(dataset, source, n, scope=None):
            print(f"{dataset:18} {n:7d}  <- {source}", file=sys.stderr)
            out.line(f"INSERT INTO geo_data_imports (dataset, source, source_date, scope, row_count) VALUES "
                     f"({sql_str(dataset)}, {sql_str(Path(source).name)}, {sql_str(source_date)}, "
                     f"{sql_str(scope)}, {n});")

        scope = "districts " + ",".join(str(d) for d in sorted(districts))
        if a.states_csv:
            record("STATES", a.states_csv, load_states(a.states_csv, out))
        if a.districts_csv:
            record("DISTRICTS", a.districts_csv, load_districts(a.districts_csv, out, None))
        urban_rows, urban_links = [], []
        if a.urban_bodies_csv:
            if not a.municipal_coverage_csv:
                p.error("--urban-bodies-csv needs --municipal-coverage-csv (it holds each body's district)")
            urban_rows, urban_links = read_urban_bodies(a.urban_bodies_csv, a.municipal_coverage_csv, districts)
            # urban bodies first, so load_villages keeps them URBAN (it never changes area_kind)
            record("URBAN_BODIES", a.urban_bodies_csv, write_urban_bodies(urban_rows, out), scope)
        for v in a.villages_csv:
            record("VILLAGES", v, load_villages(v, out, districts), scope)
        if urban_links:
            record("URBAN_COVERAGE", a.municipal_coverage_csv, write_urban_coverage(urban_links, out), scope)
        if a.rural_bodies_csv:
            record("RURAL_BODY_TYPES", a.rural_bodies_csv, load_rural_body_types(a.rural_bodies_csv, out))
        for w in a.wards_csv:
            record("WARDS", w, load_wards(w, out, ""), scope)
        if a.pincode_csv:
            record("PINCODES", a.pincode_csv, load_pincode_offices(a.pincode_csv, out, None))
        if a.village_pincode_csv:
            record("VILLAGE_PINCODES", a.village_pincode_csv, load_village_pincodes(a.village_pincode_csv, out), scope)
        if a.serve:
            out.line("UPDATE geo_districts SET is_served = TRUE WHERE district_code IN ("
                     + ", ".join(str(d) for d in sorted(districts)) + ");")
        if not a.flyway:
            out.line("COMMIT;")
    print(f"wrote {a.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
