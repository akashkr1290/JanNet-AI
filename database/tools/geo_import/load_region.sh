#!/usr/bin/env bash
# load_region.sh - loads every geo dataset for some states into the running
# MySQL container, in the right order. Wraps geo_import.py (see README.md).
#
# usage (from the repository root, with the Docker stack up):
#   database/tools/geo_import/load_region.sh <raw-dir> <lgd-state-codes> [district codes to serve]
# example - Uttar Pradesh + Bihar, serving Ghaziabad (145) and Gaya (196):
#   database/tools/geo_import/load_region.sh ~/jannet-geo-data/raw 9,10 145,196
#
# <raw-dir> must contain (names as downloaded, see README.md):
#   lgd-<date>/            unpacked LGD CSVs (villages.<date>.csv ...)
#   boundaries-<date>/     SBM_Wards / SBM_ULBs / LGD_Villages / LGD_panchayats /
#                          Datagov_Pincode_Boundaries .geojsonl.7z
#   india_post_pincode_*.csv
#
# The MySQL password is never typed or printed: the SQL is piped into the
# mysql container, which already has its credentials in its environment.
# Set MYSQL_CONTAINER if your mysql container has another name, or MYSQL_CMD
# to use another client, e.g. MYSQL_CMD="mysql -uroot jannet_ai".
set -euo pipefail

raw="${1:?raw data folder}"
states="${2:?LGD state codes, e.g. 9,10}"
serve="${3:-}"
here="$(cd "$(dirname "$0")" && pwd)"
importer="$here/geo_import.py"

lgd="$(ls -d "$raw"/lgd-* 2>/dev/null | sort | tail -1)"
bnd="$(ls -d "$raw"/boundaries-* 2>/dev/null | sort | tail -1)"
post="$(ls "$raw"/india_post_pincode_*.csv 2>/dev/null | sort | tail -1)"
[ -n "$lgd" ] || { echo "no $raw/lgd-<date> folder" >&2; exit 1; }
[ -n "$bnd" ] || { echo "no $raw/boundaries-<date> folder" >&2; exit 1; }
lgd_date="$(basename "$lgd" | sed 's/^lgd-//')"
bnd_date="$(basename "$bnd" | sed 's/^boundaries-//')"

if [ -n "${MYSQL_CMD:-}" ]; then
  run_sql() { $MYSQL_CMD; }
else
  run_sql() {
    # jannet-mysql = container_name of the mysql service in docker/docker-compose.yml
    docker exec -i "${MYSQL_CONTAINER:-jannet-mysql}" \
      sh -c 'exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
      2> >(grep -v "Using a password on the command line" >&2)
  }
fi

step() { echo; echo "=== $*"; }

step "LGD areas, PIN links and ward lists ($lgd)"
python3 -I "$importer" lgd --dir "$lgd" --states "$states" --serve "$serve" --source-date "$lgd_date" | run_sql

if [ -n "$post" ]; then
  step "India Post offices ($(basename "$post"))"
  python3 -I "$importer" postoffices --csv "$post" --lgd-dir "$lgd" --states "$states" | run_sql
else
  step "India Post offices - skipped, no india_post_pincode_*.csv in $raw"
fi

for layer_file in "WARD:SBM_Wards" "LOCAL_BODY:SBM_ULBs" "PINCODE:Datagov_Pincode_Boundaries" \
                  "PANCHAYAT:LGD_panchayats" "VILLAGE:LGD_Villages"; do
  layer="${layer_file%%:*}"; file="$bnd/${layer_file#*:}.geojsonl.7z"
  if [ -f "$file" ]; then
    step "$layer boundaries ($(basename "$file"))"
    python3 -I "$importer" boundaries --layer "$layer" --file "$file" --lgd-dir "$lgd" \
      --states "$states" --source-date "$bnd_date" | run_sql
  else
    step "$layer boundaries - skipped, $file not found"
  fi
done

step "map centres of villages and wards"
python3 -I "$importer" centres | run_sql

echo
echo "Done. Restart the backend so it drops its cached area lists:"
echo "  docker restart jannet-backend"
