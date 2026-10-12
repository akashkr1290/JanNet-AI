# geo_import — JanNet AI's own geographic dataset

Location v2 (V34 + V36) needs no map API key. To answer "where is this point?",
the backend checks which polygon contains the point in MySQL (`ST_Contains`).
It answers "which villages / bodies does PIN 201001 cover?" from LGD tables.
This tool loads those tables.

* `geo_import.py` uses only the Python 3.9+ standard library. It writes SQL to stdout and a report to stderr.
* `load_region.sh` runs every step for some states and pipes the SQL into the
  `jannet-mysql` container. Your MySQL password is never typed or printed.

The `database/tools/lgd_import/` tool is the older one. It made `V35` from the
March 2022 LGD snapshot. `geo_import` replaces it for new loads, and both
write the same tables.

## Sources (all free)

| Data | File | Published by | Copy we download from |
|---|---|---|---|
| States, districts, sub-districts, blocks, villages, Gram Panchayats, urban bodies, village → body, village → PIN, urban body → PIN, ward lists | `lgd-<date>/*.csv` (13 files) | Local Government Directory, lgdirectory.gov.in (MoPR) | Daily mirror `github.com/ramSeraph/opendata`, release `lgd-latest-extra1`. The LGD site itself needs a captcha. |
| Post offices with PIN, office type and lat/long | `india_post_pincode_2021.csv` | Department of Posts ("All India Pincode Directory", data.gov.in) | `github.com/harshvardhaniimi/IndiaPIN` `data-raw/pincode.csv`, December 2021, used because the data.gov.in API timed out |
| Ward boundaries of towns | `SBM_Wards.geojsonl.7z` | Swachh Bharat Mission (Urban), ward maps approved by ULB nodal officers | `github.com/ramSeraph/indian_admin_boundaries`, release `urban` |
| Town boundaries | `SBM_ULBs.geojsonl.7z` | Swachh Bharat Mission (Urban) | same, release `urban` |
| Village boundaries with LGD codes | `LGD_Villages.geojsonl.7z` | Bharatmaps / LGD (mapservice.gov.in) | same, release `villages` |
| Gram Panchayat boundaries | `LGD_panchayats.geojsonl.7z` | Bharatmaps / LGD | same, release `panchayats` |
| PIN code boundaries | `Datagov_Pincode_Boundaries.geojsonl.7z` | data.gov.in "All India Pincode Boundary" (GODL licence) | same, release `postal` |

Every row stores its source (`source`, `mapping_source`), its download date and
a verification status:

* `PUBLISHED` means a government portal published it. We load it unchanged, and our staff have not checked it yet.
* `VERIFIED` means an Admin or officer of this deployment confirmed it.
* `UNVERIFIED` means the row was entered by hand, or we derived it, and nobody has confirmed it yet.

### What the data does not have (checked 2026-10-11, UP + Bihar)

* **Some town wards have no map.** Ghaziabad Nagar Nigam has 100 wards, and SBM maps 94 of them. For points in the other wards (e.g. parts of Indirapuram), the citizen picks the ward. That complaint is marked `REQUIRES_VERIFICATION`.
* **LGD ward lists can be an older delimitation.** For example, LGD lists 60 wards for Ghaziabad. Where SBM maps a town's wards, the importer switches the LGD list for that town off, so only one ward list is offered.
* **Gram Panchayat wards have lists but no maps.** The citizen chooses, and the complaint is marked `REQUIRES_VERIFICATION`.
* **Village maps:** UP has 101,729 of 110,308 villages, and Bihar has 43,332 of 48,927.
* **Towns without a Census 2011 code** (created after 2011) are linked to LGD only by an exact name match in the same district. Each link is listed in the report. Cantonment boards are never linked to the city's Nagar Nigam.

## Download (once, ~750 MB)

Use the step-by-step guide in `docs/LOCATION_V2.md` §"Dataset". It ends with this layout:

```
~/jannet-geo-data/raw/
  india_post_pincode_2021.csv
  lgd-2026-10-11/          13 *.csv.7z files, unpacked with 7z x  (folder name = download date)
  boundaries-2026-10-11/   5 *.geojsonl.7z files (kept compressed, read with 7z on the fly)
```

## Load

Run this with the Docker stack up, from the repository root. It needs `python3` and `p7zip-full`.

```bash
database/tools/geo_import/load_region.sh ~/jannet-geo-data/raw 9,10 145,196
docker restart jannet-backend
```

* `9,10` are the LGD state codes for Uttar Pradesh and Bihar.
* `145,196` are the districts this deployment serves (Ghaziabad, Gaya). Complaints outside them are flagged "out of jurisdiction".

Timings for UP + Bihar, measured on the dev MySQL:

| Step | Time |
|---|---|
| LGD | ~2 min |
| India Post | < 1 min |
| Ward maps | ~30 s |
| PIN maps | ~10 s |
| Villages | see `docs/LOCATION_V2.md` |

A re-run updates rows in place. LGD codes and source ids are the keys. To add another state later, run the same command with its LGD code. For example, `9,10,20` adds Jharkhand. Gram Panchayat ward lists are loaded only for served districts unless you pass `--gp-wards all` to `geo_import.py lgd`, because UP + Bihar alone have 835,000 of them.

Single steps, e.g. only refreshing the ward maps:

```bash
python3 database/tools/geo_import/geo_import.py boundaries --layer WARD \
    --file ~/jannet-geo-data/raw/boundaries-2026-10-11/SBM_Wards.geojsonl.7z \
    --lgd-dir ~/jannet-geo-data/raw/lgd-2026-10-11 --states 9,10 --source-date 2026-10-11 \
  | docker exec -i jannet-mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
```

The history of every load is in `geo_data_imports`. Each boundary load
prints `sent` (features in the file) and `stored_valid` (polygons MySQL
accepted). Polygons MySQL calls invalid are skipped, because `ST_Contains` would fail on them.

## Check a point

```sql
SET @p = ST_GeomFromText('POINT(77.4538 28.6692)', 4326, 'axis-order=long-lat');
SELECT layer, ref_code, name, verification_status FROM geo_boundaries WHERE ST_Contains(geom, @p);
-- Ghaziabad centre: LOCAL_BODY 248988 Ghaziabad, PINCODE 201002, WARD "Ward 91 - Kavi Nagar"
```
