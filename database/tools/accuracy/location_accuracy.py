#!/usr/bin/env python3
"""Location accuracy harness for JanNet AI (UP + Bihar plan, WP5).

Measures how well the backend's point -> area lookup (GET /api/v1/geo/locate,
service/geo/AreaResolver) finds the right place, using points whose answer is
known from an independent source:

  1. India Post offices (latitude / longitude, PIN, district), exported from the
     pincode_offices table that database/tools/geo_import loaded
     (see README.md, "Export the points").
  2. A small hand-made list of known places (block offices, DM offices ...)
     with the block they are in (known_places.csv).

For each point it calls the API and records what came back. Only an answer
from published boundaries (areaSource BOUNDARY) counts as "found": the
backend's fallback (areaSource POST_OFFICE) takes the district of the nearest
post office, which would make the India Post check agree with itself.

Standard library only (Python 3.9+). Nothing is written to the backend: the
harness only reads. The password is asked for at the prompt (never on the
command line, never stored).

  python3 location_accuracy.py offices points.tsv --base-url https://.../api/v1 --user 9876543210 --sample 600
  python3 location_accuracy.py places known_places.csv --base-url ... --user ...

Outputs (in --out, default ./accuracy-<date>/): report.md, results.csv
(every point) and mismatches.csv (district / PIN / block that disagree).
"""
from __future__ import annotations

import argparse
import collections
import csv
import datetime as dt
import getpass
import json
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

# The backend allows 100 requests a minute per user (RateLimitingFilter).
DEFAULT_PER_MINUTE = 80


# --------------------------------------------------------------------------- input

@dataclass
class Point:
    """One point with the answer we expect."""
    ref: str                       # office name / place name
    lat: float
    lng: float
    state_code: int | None = None
    district_code: int | None = None
    district_name: str | None = None
    pincode: str | None = None
    block_name: str | None = None
    local_body_name: str | None = None


def read_offices(path: Path) -> list[Point]:
    """Tab-separated export of pincode_offices (README.md): header
    pincode, office_name, district_code, district_name, state_code, latitude, longitude."""
    points = []
    with path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            try:
                lat, lng = float(row["latitude"]), float(row["longitude"])
            except (TypeError, ValueError):
                continue
            points.append(Point(
                ref=row["office_name"].strip(),
                lat=lat, lng=lng,
                state_code=_int(row.get("state_code")),
                district_code=_int(row.get("district_code")),
                district_name=(row.get("district_name") or "").strip() or None,
                pincode=(row.get("pincode") or "").strip() or None,
            ))
    return points


def read_places(path: Path) -> list[Point]:
    """known_places.csv: name, latitude, longitude, district, block[, local_body][, note]."""
    points = []
    with path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            name = (row.get("name") or "").strip()
            if not name or name.startswith("#"):
                continue
            try:
                lat, lng = float(row["latitude"]), float(row["longitude"])
            except (TypeError, ValueError, KeyError):
                raise SystemExit(f"known places: '{name}' needs numeric latitude and longitude")
            points.append(Point(
                ref=name, lat=lat, lng=lng,
                district_name=(row.get("district") or "").strip() or None,
                block_name=(row.get("block") or "").strip() or None,
                local_body_name=(row.get("local_body") or "").strip() or None,
            ))
    return points


def sample(points: list[Point], n: int | None, seed: int) -> list[Point]:
    """n points spread evenly over the states (same seed = same points)."""
    if not n or n >= len(points):
        return points
    rnd = random.Random(seed)
    by_state: dict[int | None, list[Point]] = collections.defaultdict(list)
    for p in points:
        by_state[p.state_code].append(p)
    states = sorted(by_state, key=lambda s: (s is None, s))
    per_state = max(1, n // len(states))
    picked = []
    for s in states:
        group = by_state[s]
        picked.extend(rnd.sample(group, min(per_state, len(group))))
    return picked


def _int(v) -> int | None:
    try:
        return int(str(v).strip())
    except (TypeError, ValueError):
        return None


# --------------------------------------------------------------------------- API

class Api:
    """Logged-in client for the JanNet AI API, paced under the rate limit."""

    def __init__(self, base_url: str, per_minute: int, timeout: float = 30):
        self.base = base_url.rstrip("/")
        self.gap = 60.0 / max(1, per_minute)
        self.timeout = timeout
        self.token: str | None = None
        self._last = 0.0

    def login(self, identifier: str, password: str) -> None:
        body = self._request("POST", "/auth/login", {"identifier": identifier, "password": password}, auth=False)
        token = body.get("accessToken") if isinstance(body, dict) else None
        if not token:
            raise SystemExit("login did not return a token - use a citizen test account without MFA")
        self.token = token

    def locate(self, lat: float, lng: float) -> dict:
        q = urllib.parse.urlencode({"lat": f"{lat:.6f}", "lng": f"{lng:.6f}"})
        return self._request("GET", f"/geo/locate?{q}")

    def _request(self, method: str, path: str, payload: dict | None = None, auth: bool = True, tries: int = 4):
        for attempt in range(tries):
            wait = self.gap - (time.monotonic() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()
            data = json.dumps(payload).encode() if payload is not None else None
            req = urllib.request.Request(self.base + path, data=data, method=method)
            req.add_header("Accept", "application/json")
            req.add_header("User-Agent", "jannet-accuracy-harness/1.0")
            if data is not None:
                req.add_header("Content-Type", "application/json")
            if auth and self.token:
                req.add_header("Authorization", f"Bearer {self.token}")
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    return json.loads(r.read().decode() or "null")
            except urllib.error.HTTPError as e:
                if e.code == 429 and attempt + 1 < tries:
                    time.sleep(61)  # rate-limit window
                    continue
                if e.code in (502, 503, 504) and attempt + 1 < tries:
                    time.sleep(5 * (attempt + 1))
                    continue
                detail = e.read().decode(errors="replace")[:200]
                raise ApiError(f"{method} {path.split('?')[0]} -> HTTP {e.code}: {detail}") from None
            except (urllib.error.URLError, TimeoutError) as e:
                if attempt + 1 < tries:
                    time.sleep(5 * (attempt + 1))
                    continue
                raise ApiError(f"{method} {path.split('?')[0]} -> {e}") from None
        raise ApiError(f"{method} {path.split('?')[0]} -> gave up")


class ApiError(Exception):
    pass


# --------------------------------------------------------------------------- comparison

def norm(name: str | None) -> str:
    """Lower-case letters and digits only: 'Bara Banki' == 'Barabanki'."""
    return re.sub(r"[^a-z0-9]", "", (name or "").lower())


def same_name(a: str | None, b: str | None) -> bool:
    na, nb = norm(a), norm(b)
    return bool(na) and bool(nb) and (na == nb or na in nb or nb in na)


@dataclass
class Result:
    point: Point
    area_source: str = "ERROR"
    district_code: int | None = None
    district_name: str | None = None
    block_name: str | None = None
    local_body_name: str | None = None
    village_name: str | None = None
    ward_name: str | None = None
    pincode: str | None = None
    error: str | None = None
    district_ok: bool | None = None   # None = not comparable
    pincode_ok: bool | None = None
    block_ok: bool | None = None
    body_ok: bool | None = None


def compare(p: Point, area: dict | None) -> Result:
    r = Result(point=p)
    if not isinstance(area, dict):
        r.error = "empty answer"
        return r
    r.area_source = area.get("areaSource") or "NONE"
    d = area.get("district") or {}
    r.district_code = d.get("code")
    r.district_name = d.get("name")
    r.block_name = (area.get("block") or {}).get("name")
    r.local_body_name = (area.get("localBody") or {}).get("name")
    r.village_name = (area.get("village") or {}).get("name")
    r.ward_name = (area.get("ward") or {}).get("name")
    r.pincode = area.get("pincode")

    found = r.area_source == "BOUNDARY"
    if found and (p.district_code is not None or p.district_name):
        if p.district_code is not None and r.district_code is not None:
            r.district_ok = r.district_code == p.district_code
        else:
            r.district_ok = same_name(r.district_name, p.district_name)
    if found and p.pincode and r.pincode:
        r.pincode_ok = r.pincode == p.pincode
    if p.block_name:
        r.block_ok = found and same_name(r.block_name, p.block_name)
    if p.local_body_name:
        r.body_ok = found and same_name(r.local_body_name, p.local_body_name)
    return r


# --------------------------------------------------------------------------- report

def pct(n: int, d: int) -> str:
    return f"{100.0 * n / d:.1f} %" if d else "-"


def summarise(results: list[Result], kind: str, source: str, base_url: str) -> str:
    total = len(results)
    errors = [r for r in results if r.error]
    by_source = collections.Counter(r.area_source for r in results if not r.error)
    found = [r for r in results if r.area_source == "BOUNDARY"]

    def rate(attr: str) -> tuple[int, int]:
        vals = [getattr(r, attr) for r in results if getattr(r, attr) is not None]
        return sum(1 for v in vals if v), len(vals)

    lines = [
        f"# Location accuracy - {kind}",
        "",
        f"- Run: {dt.datetime.now().strftime('%Y-%m-%d %H:%M')}",
        f"- API: `{base_url}`",
        f"- Points: {total} from `{source}`",
        f"- API errors: {len(errors)}",
        "",
        "## How the area was found",
        "",
        "| areaSource | points | share |",
        "|---|---:|---:|",
    ]
    for s, n in by_source.most_common():
        lines.append(f"| {s} | {n} | {pct(n, total)} |")
    lines += [
        "",
        "BOUNDARY = published polygons contain the point (the only answer counted below).",
        "POST_OFFICE = no polygon: the backend only guessed the district from the nearest",
        "post office, so it is not counted (it would agree with India Post by construction).",
        "",
        "## Accuracy (boundary answers only)",
        "",
        "| check | correct | compared | rate |",
        "|---|---:|---:|---:|",
    ]
    for label, attr in (("District", "district_ok"), ("PIN code", "pincode_ok"),
                        ("Block", "block_ok"), ("Gram Panchayat / town", "body_ok")):
        ok, n = rate(attr)
        if n:
            lines.append(f"| {label} | {ok} | {n} | {pct(ok, n)} |")
    lines += [
        "",
        "## Coverage (boundary answers)",
        "",
        "| field returned | points | share of all points |",
        "|---|---:|---:|",
        f"| village | {sum(1 for r in found if r.village_name)} | {pct(sum(1 for r in found if r.village_name), total)} |",
        f"| block | {sum(1 for r in found if r.block_name)} | {pct(sum(1 for r in found if r.block_name), total)} |",
        f"| Gram Panchayat / town | {sum(1 for r in found if r.local_body_name)} | {pct(sum(1 for r in found if r.local_body_name), total)} |",
        f"| ward | {sum(1 for r in found if r.ward_name)} | {pct(sum(1 for r in found if r.ward_name), total)} |",
        f"| PIN | {sum(1 for r in found if r.pincode)} | {pct(sum(1 for r in found if r.pincode), total)} |",
        "",
        "Mismatches are in `mismatches.csv` with an OpenStreetMap link for each. India Post",
        "coordinates (2021) are themselves sometimes wrong: check a sample of mismatches by hand",
        "and note the cause (our data, India Post data, or a point on a border).",
    ]
    if errors:
        lines += ["", "## First API errors", ""] + [f"- {r.point.ref}: {r.error}" for r in errors[:5]]
    return "\n".join(lines) + "\n"


RESULT_FIELDS = ["ref", "latitude", "longitude", "expected_district", "got_district", "district_ok",
                 "expected_pincode", "got_pincode", "pincode_ok", "expected_block", "got_block", "block_ok",
                 "expected_local_body", "got_local_body", "body_ok", "got_village", "got_ward",
                 "area_source", "error", "map"]


def result_row(r: Result) -> dict:
    p = r.point
    return {
        "ref": p.ref, "latitude": f"{p.lat:.6f}", "longitude": f"{p.lng:.6f}",
        "expected_district": p.district_name or p.district_code or "", "got_district": r.district_name or "",
        "district_ok": _flag(r.district_ok),
        "expected_pincode": p.pincode or "", "got_pincode": r.pincode or "", "pincode_ok": _flag(r.pincode_ok),
        "expected_block": p.block_name or "", "got_block": r.block_name or "", "block_ok": _flag(r.block_ok),
        "expected_local_body": p.local_body_name or "", "got_local_body": r.local_body_name or "",
        "body_ok": _flag(r.body_ok), "got_village": r.village_name or "", "got_ward": r.ward_name or "",
        "area_source": r.area_source, "error": r.error or "",
        "map": f"https://www.openstreetmap.org/?mlat={p.lat:.6f}&mlon={p.lng:.6f}#map=15/{p.lat:.6f}/{p.lng:.6f}",
    }


def _flag(v: bool | None) -> str:
    return "" if v is None else ("yes" if v else "NO")


def is_mismatch(r: Result) -> bool:
    return any(v is False for v in (r.district_ok, r.pincode_ok, r.block_ok, r.body_ok))


def write_outputs(out: Path, results: list[Result], report: str) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.md").write_text(report, encoding="utf-8")
    for name, rows in (("results.csv", results), ("mismatches.csv", [r for r in results if is_mismatch(r)])):
        with (out / name).open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=RESULT_FIELDS)
            w.writeheader()
            for r in rows:
                w.writerow(result_row(r))


# --------------------------------------------------------------------------- main

def run(args: argparse.Namespace) -> int:
    path = Path(args.file)
    points = read_offices(path) if args.kind == "offices" else read_places(path)
    points = sample(points, args.sample, args.seed)
    if not points:
        raise SystemExit(f"no usable points in {path}")
    api = Api(args.base_url, args.per_minute)
    password = getpass.getpass(f"Password for {args.user}: ")
    api.login(args.user, password)
    minutes = len(points) * api.gap / 60
    print(f"{len(points)} points, about {minutes:.0f} min at {args.per_minute}/min", file=sys.stderr)

    results = []
    for i, p in enumerate(points, 1):
        try:
            results.append(compare(p, api.locate(p.lat, p.lng)))
        except ApiError as e:
            r = Result(point=p)
            r.error = str(e)
            results.append(r)
            if "HTTP 401" in str(e) or "HTTP 403" in str(e):
                print("login expired or refused - stopping early; partial results are written", file=sys.stderr)
                break
        if i % 50 == 0 or i == len(points):
            print(f"  {i}/{len(points)}", file=sys.stderr)

    out = Path(args.out or f"accuracy-{dt.date.today().isoformat()}-{args.kind}")
    write_outputs(out, results, summarise(results, args.kind, path.name, args.base_url))
    print(f"written: {out}/report.md, results.csv, mismatches.csv", file=sys.stderr)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("kind", choices=["offices", "places"], help="offices = India Post export, places = known_places.csv")
    ap.add_argument("file", help="points file")
    ap.add_argument("--base-url", required=True, help="API base, e.g. https://jannet-api.example.workers.dev/api/v1")
    ap.add_argument("--user", required=True, help="login (phone / email) of a citizen test account")
    ap.add_argument("--sample", type=int, default=None, help="use n points spread over the states (default: all)")
    ap.add_argument("--seed", type=int, default=2026, help="random seed for --sample (same seed = same points)")
    ap.add_argument("--per-minute", type=int, default=DEFAULT_PER_MINUTE, help="requests per minute (limit is 100)")
    ap.add_argument("--out", default=None, help="output folder")
    return run(ap.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
