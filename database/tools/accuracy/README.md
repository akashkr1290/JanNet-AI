# Location accuracy harness (WP5)

Measures how often the backend's point → area lookup (`GET /api/v1/geo/locate`,
`service/geo/AreaResolver`) finds the right district, PIN, block and Gram Panchayat /
town for UP and Bihar. Plan: `plans/2026-10-14_UP_BIHAR_LOCATION_ACCURACY_PLAN.md`
(project docs). Owner: Nandini.

Python 3.9+, standard library only. It only reads from the API. The password is asked
at the prompt and never stored or printed.

## What is counted

* Only answers with `areaSource = BOUNDARY` (a published polygon contains the point)
  are scored. When no polygon covers a point the backend takes the district of the
  nearest post office (`POST_OFFICE`); scoring that against India Post would agree
  with itself, so those points are only counted in the "how the area was found" table.
* District: by LGD district code (India Post export) or by name (known places).
* PIN: compared only when the backend returned a PIN polygon.
* Block / Gram Panchayat: by name, known places only.

## 1. Export the points (Akash's laptop, from the `jn-geo` database)

```bash
docker exec jn-geo sh -c 'exec mysql -B -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "
  SELECT p.pincode, p.office_name, p.district_code, d.name AS district_name, d.state_code,
         p.latitude, p.longitude
  FROM pincode_offices p JOIN geo_districts d ON d.district_code = p.district_code
  WHERE d.state_code IN (9, 10) AND p.latitude IS NOT NULL"' > up_bihar_offices.tsv
```

`up_bihar_offices.tsv` is data, not code: do not commit it.

## 2. Fill `known_places.csv`

About 30 places whose block is certain (block / BDO offices, DM offices, villages you
know), with coordinates copied from the map. Never guess a block.

## 3. Run

```bash
python3 database/tools/accuracy/location_accuracy.py offices up_bihar_offices.tsv \
  --base-url https://<api host>/api/v1 --user <citizen test login> --sample 600
python3 database/tools/accuracy/location_accuracy.py places database/tools/accuracy/known_places.csv \
  --base-url https://<api host>/api/v1 --user <citizen test login>
```

* `--sample 600` = 300 points per state, the same 600 every run (`--seed`), so a run
  before and after a data load can be compared. Without `--sample` every office is used.
* The backend allows 100 requests a minute; the harness sends 80 (`--per-minute`), so
  600 points take about 8 minutes.
* Use a citizen test account (staff accounts may need MFA).

Output folder `accuracy-<date>-<kind>/`: `report.md` (tables), `results.csv` (every
point) and `mismatches.csv` (with an OpenStreetMap link per point). India Post
coordinates (2021) are sometimes wrong: check about 50 mismatches by hand and write the
cause next to each (our data / India Post data / border).

## Tests

```bash
python3 -m unittest discover -s database/tools/accuracy -p 'test_*.py'
```
