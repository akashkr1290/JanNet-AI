# Location v2 — own dataset, no API key (V34 / V35 / V36)

The complaint location (PIN, village / town, Gram Panchayat / Nagar Nigam, ward)
is worked out **from our own MySQL tables**. No Google key is needed. The map
picture is MapLibre + OpenFreeMap (free, no key). Google code stays in the
project but is **off** by default (`GOOGLE_MAPS_ENABLED=false`).

## What changed

| Area | Change | Main files |
|---|---|---|
| Database | V34: LGD hierarchy (state → district → tehsil/block → Gram Panchayat / urban body → village → ward), PIN tables, area on complaints / officers / departments. V35: Gaya + Ghaziabad seed. **V36:** `geo_boundaries` (MySQL spatial, SRID 4326), `pincode_local_bodies` (many-to-many), `complaint_assignments` (history), source + verification status on geo rows, `locations.location_verification_status`, `suggested_ward_id`. **V37:** development block as a routing level: `departments.block_code` (scope `B<code>`, most specific first LB > B > D > ALL), `locations.block_code` | `database/migrations/V34…V37` |
| Dataset tool | `geo_import` loads LGD, India Post and boundary files for chosen states, repeatable, logged in `geo_data_imports` | `database/tools/geo_import/` |
| Backend | `GET /geo/locate` (point → area by `ST_Contains`), `/geo/search`, `/geo/pincodes?prefix=`, `/geo/pincodes/{pin}/local-bodies`, `/geo/wards/{id}/boundary`; `GET /complaints/{id}/assignment`; staff queue `GET/PATCH /complaints/location-verification`; assignment history on every routing / reassignment. **V37:** `block` on every pin result (village's LGD block, else the Gram Panchayat's; none in a town), `blockCode`/`blockName` on the complaint location, `GET /geo/blocks?districtCode=`, block departments in Admin, routing Gram Panchayat / town → block → district → global | `service/geo/*`, `client/osm/*`, `controller/ComplaintLocationController.java` |
| App | MapLibre map, ward outline, PIN autocomplete, "Who is handling it" card, staff **Location Check** tab (Department Head, Verification Team). **V37:** block on the "Is this the place?" card, the complaint summary and the officers' area line; Admin department form can pick a block; read-only map preview re-sized and re-centred after the style loads (blue-box fix) | `lib/core/maps/*`, `lib/features/complaints/location/*`, `screens/location_check_screen.dart` |

## The one rule: a ward is never guessed

```
GPS point
 ├─ inside a published ward map          → ward set, status VERIFIED_BOUNDARY
 ├─ no ward map for that spot            → citizen may pick a ward from the LGD list
 │                                         status REQUIRES_VERIFICATION (reason in the queue)
 └─ nearest ward / colony-name match     → only a *suggestion* for staff (suggested_ward_id),
                                           never used for routing
```

Staff confirm the ward in **Location Check**; status becomes `STAFF_VERIFIED`.
The citizen only sees "The office will check the exact ward".

## The four situations (citizen)

| Situation | What happens |
|---|---|
| Photo with the app camera, at the spot | GPS at the click → pin → "Is this the place?" → Yes → Submit |
| Saved draft ("Save & Report Later") | GPS saved at the click is used — same as above |
| Gallery photo with location | Photo GPS (Android: original file via `MainActivity.pickOriginal`) |
| Photo without location | "Are you at the place now?" **Yes**: phone GPS now. **No**: PIN → village/town → body → ward, map following each answer |

The map with the red pin is visible in every step. The AI category and
confidence are shown on the complaint; below the threshold the complaint goes
to the Verification Team instead of being routed.

## Where the data comes from (UP + Bihar loaded; Ghaziabad = pilot)

See `database/tools/geo_import/README.md` for the full table. Short version:

| Data | Source | Status in the database |
|---|---|---|
| States … villages, Gram Panchayats, urban bodies, village/town → PIN, ward lists | LGD (lgdirectory.gov.in), via the daily mirror `ramSeraph/opendata` | `PUBLISHED` (not yet checked by our staff) |
| Post offices + lat/long | India Post directory (Dec 2021) | `PUBLISHED` |
| Ward / town boundaries | Swachh Bharat Mission (Urban) | `PUBLISHED` |
| Village / Gram Panchayat boundaries | Bharatmaps / LGD | `PUBLISHED` |
| PIN boundaries | data.gov.in "All India Pincode Boundary" (GODL) | `PUBLISHED` |

Honest gaps (checked 2026-10-11): Ghaziabad Nagar Nigam has 100 wards and the
ward map covers 94; LGD lists 60 for it. Gram Panchayat wards have lists but no
maps. Nothing here is presented as an official ward-by-ward verification — the
status column says what each row is.

## Map picture

`lib/core/maps/maps_config.dart` — MapLibre GL + OpenFreeMap `liberty` style.
To use another provider later (for example our own India tiles) build with
`--dart-define=MAP_STYLE_URL=https://…/style.json`. No code change.
Android needs **JDK 21** to build the APK with `maplibre_gl` 0.27.x.

## Search

`GET /geo/search`: own dataset first (PIN, post office, ward, town, village),
then any place in India from OpenStreetMap data: **Photon** first (free, no key,
built for type-as-you-type, tolerant of spelling), **Nominatim** when Photon finds
nothing or is down (1 request / second, the search waits for its turn, cached,
identified by `NOMINATIM_USER_AGENT` / `NOMINATIM_EMAIL`). `PHOTON_ENABLED=false`
turns Photon off; `PHOTON_BASE_URL` points to your own Photon server later. Search
only moves the pin; the area is always recomputed from the boundaries.

## Optional: Google Maps later

Google asks new Indian accounts for a one-time payment of at least ₹1,000 before
billing becomes active, so it is **not** used now. If the team decides to
enable it: set `GOOGLE_MAPS_ENABLED=true` and `GOOGLE_MAPS_SERVER_KEY` (daily caps
in `.env.example`). Nothing else changes.

## Load the data

```bash
database/tools/geo_import/load_region.sh ~/jannet-geo-data/raw 9,10 145,196
docker restart jannet-backend
```

Large files stay outside the repository and the Docker images.

## Local test commands

```bash
scripts/test-local.sh pub       # once: refresh flutter/pubspec.lock (maplibre_gl)
scripts/test-local.sh backend   # MySQL 8 + all JUnit tests (incl. V34–V36)
scripts/test-local.sh flutter   # flutter analyze + flutter test
scripts/test-local.sh ai        # ai-service pytest
```

## Test plan (what is covered)

| Case | Where |
|---|---|
| Point inside a ward map → ward set and verified; no ward map → no ward guessed, staff must verify; rough GPS fix; ward of another body rejected; district not served; invalid PIN | `LocationServiceAreaTest` |
| Name matching, PIN covering several bodies, ambiguous village name | `AreaResolverTest`, `GeoNamesTest`, `geo_v36_test.dart` |
| Search: dataset first, OpenStreetMap only for few results, never for digits, PIN centre, unknown PIN not invented, result limit | `GeoSearchServiceTest` |
| OpenStreetMap parsing, switched off, 1 request / second, server down → empty answer | `NominatimClientTest` |
| Citizen sees no officer name; staff queue per role; confirm ward is audited; no location → 409 | `LocationVerificationServiceTest` |
| Routing writes the assignment history (RULE / FALLBACK) | `DepartmentAssignmentServiceTest` |
| Missing EXIF / GPS, poor accuracy, moved pin | `incident_location_test.dart`, `incident_area_test.dart` |
| Low AI confidence → Verification Team, not routed | existing `DepartmentAssignmentServiceTest`, AI-service tests |
| Migrations V1–V36 on an empty database | `scripts/test-local.sh backend` |
| Real points (Ghaziabad centre, Raj Nagar, Crossings Republik, Indirapuram, Bodh Gaya, a point in the sea) | manual check in `database/tools/geo_import/README.md` "Check a point" |
