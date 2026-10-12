# LGD / PIN code import

Loads real administrative areas into the V34 tables. The pilot districts
(Gaya 196, Ghaziabad 145) are already seeded by `V35` from the LGD snapshot of
March 2022; use this tool to refresh them from a current download, to add
**wards** and **PIN codes**, or to add another district.

## 1. Download (free, Government Open Data License – India)

| File | Where |
|---|---|
| States, Districts, Sub-districts, Blocks | lgdirectory.gov.in → Download Directory → *All States / Districts / Sub-Districts / Blocks of India* (CSV) |
| Village directory with local bodies | lgdirectory.gov.in → *Villages with Local Body mapping* (state-wise CSV) |
| Urban local bodies + coverage | lgdirectory.gov.in → *All Urban Local Bodies of India*, *Urban Local Body coverage* |
| PRI (rural) local bodies | lgdirectory.gov.in → *All PRI Local Bodies of India* |
| **Wards** | lgdirectory.gov.in → *Wards of Urban Local Bodies* (state) and *Wards of PRI Local Bodies* (state) |
| PIN → post office | data.gov.in → *All India Pincode Directory* |
| PIN → village | data.gov.in / AIKosh → *LGD Villages with PIN codes* |

The LGD site asks for a captcha, so these are downloaded by hand. Keep the
files out of git (they are large); only the generated SQL is applied.

## 2. Generate SQL

Find the LGD district codes in the districts file (Gaya = 196, Ghaziabad = 145).

```bash
python3 database/tools/lgd_import/lgd_import.py \
  --districts 196,145 \
  --states-csv states.csv --districts-csv districts.csv \
  --villages-csv bihar_villages.csv --villages-csv up_villages.csv \
  --urban-bodies-csv urban_bodies.csv --municipal-coverage-csv urban_coverage.csv \
  --rural-bodies-csv pri_bodies.csv \
  --wards-csv up_urban_wards.csv --wards-csv bihar_pri_wards.csv \
  --pincode-csv pincode_directory.csv \
  --source-date 2026-10-12 --serve \
  -o geo_load.sql
```

`--serve` marks the districts as served (complaints from other districts are
flagged OUT_OF_JURISDICTION for Admin review). Every option except
`--districts` and `-o` is optional; give only the files you downloaded.

## 3. Apply

Local Docker stack:

```bash
docker exec -i jannet-mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' < geo_load.sql
```

The SQL is idempotent (`ON DUPLICATE KEY UPDATE` / `INSERT IGNORE`), so it can be
re-run. Each run is recorded in `geo_data_imports`.

## What the matching relies on

* `geo_villages.name_key` – lower-case letters/digits only (same rule as the
  backend's `GeoNames.normalize`). "Jolah Bigha" → `jolahbigha`.
* Hamlets (*tola*) are not LGD villages; citizens pick the revenue village
  they belong to.
* Wards without a boundary are chosen from the body's ward list by the
  citizen; add colony names to `ward_localities` (Admin → Wards) so urban
  wards can be found from Google's colony name.
