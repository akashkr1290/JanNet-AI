-- V34__location_hierarchy.sql
-- Real administrative areas of India for complaint location, officer and
-- department jurisdiction (location upgrade, 2026-10-11).
--
-- Why: until V33 the platform assumed ONE municipality (app.geo.municipal-*
-- bounding box + a few hand-made wards). A real deployment covers villages
-- (Gram Panchayat -> panchayat wards) and cities (Nagar Nigam -> municipal
-- wards). The ward a complaint belongs to must come from government data,
-- not from free text.
--
-- Source of the reference data: Local Government Directory (LGD,
-- https://lgdirectory.gov.in, Government Open Data License - India). LGD codes
-- are the PRIMARY KEYS below, so a fresh LGD download can be re-imported
-- with database/tools/lgd_import/lgd_import.py without changing any id.
-- PIN codes come from India Post's All India Pincode Directory (data.gov.in).
--
--   geo_states 1-* geo_districts 1-* geo_subdistricts 1-* geo_villages
--                  geo_districts 1-* geo_blocks
--   local_bodies  (Gram Panchayat / Nagar Nigam / Nagar Parishad ...)
--       *-* geo_villages via village_local_bodies (LGD: one revenue village
--           can be split between two Gram Panchayats)
--       1-* wards.local_body_code        (ward -> its body)
--   village_pincodes, pincode_offices    (PIN -> villages / post offices)
--   ward_localities                      (urban colony/sector -> ward)
--
-- Read by (backend): service/geo/AdminAreaService (lookups, PIN search),
-- service/geo/AreaResolver (GPS/Google address -> village/body/ward),
-- service/complaint/LocationService (stores the resolved codes on locations).
--
-- Compatibility: every new column is NULLable, so existing rows and older
-- app versions keep working. The pilot's placeholder wards (Ward 1/2/3,
-- UNASSIGNED) stay as "legacy" wards with local_body_code NULL.

-- ---------------------------------------------------------------------------
-- 1. Administrative hierarchy (LGD codes as primary keys)
-- ---------------------------------------------------------------------------

CREATE TABLE geo_states (
    state_code     INT UNSIGNED  NOT NULL PRIMARY KEY,   -- LGD state code (e.g. 10 = Bihar)
    name           VARCHAR(100)  NOT NULL,
    census_code    VARCHAR(10)   NULL,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_geo_states_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='LGD states / union territories';

CREATE TABLE geo_districts (
    district_code  INT UNSIGNED  NOT NULL PRIMARY KEY,   -- LGD district code (e.g. 196 = Gaya)
    state_code     INT UNSIGNED  NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    census_code    VARCHAR(10)   NULL,
    -- TRUE = this deployment accepts complaints from the district. Replaces
    -- the single app.geo.municipal-* box for out-of-jurisdiction checks
    -- (AreaResolver / LocationService). Admin turns it on per district.
    is_served      BOOLEAN       NOT NULL DEFAULT FALSE,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_geo_districts_state FOREIGN KEY (state_code) REFERENCES geo_states (state_code),
    KEY idx_geo_districts_state (state_code, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='LGD districts; is_served marks the deployment''s jurisdiction';

CREATE TABLE geo_subdistricts (
    subdistrict_code INT UNSIGNED NOT NULL PRIMARY KEY,  -- LGD tehsil / anchal / block-circle code
    district_code    INT UNSIGNED NOT NULL,
    name             VARCHAR(100) NOT NULL,
    census_code      VARCHAR(10)  NULL,
    CONSTRAINT fk_geo_subdistricts_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    KEY idx_geo_subdistricts_district (district_code, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='LGD sub-districts (tehsil / taluka / anchal)';

CREATE TABLE geo_blocks (
    block_code     INT UNSIGNED  NOT NULL PRIMARY KEY,   -- LGD development block (prakhand) code
    district_code  INT UNSIGNED  NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    CONSTRAINT fk_geo_blocks_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    KEY idx_geo_blocks_district (district_code, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='LGD development blocks';

-- A Gram Panchayat (rural) or an urban local body (Nagar Nigam, Nagar
-- Parishad, Nagar Panchayat ...). Complaints, wards, officers and
-- departments are scoped by this code.
CREATE TABLE local_bodies (
    local_body_code  INT UNSIGNED NOT NULL PRIMARY KEY,  -- LGD local body code
    name             VARCHAR(150) NOT NULL,
    -- RURAL = Panchayati Raj body, URBAN = municipal body
    area_kind        VARCHAR(10)  NOT NULL,
    -- LGD "local body type" (e.g. Gram Panchayat, Municipal Corporation);
    -- kept as text because LGD type codes differ between states.
    body_type        VARCHAR(60)  NOT NULL,
    state_code       INT UNSIGNED NOT NULL,
    district_code    INT UNSIGNED NULL,      -- NULL only when LGD gives no district
    block_code       INT UNSIGNED NULL,      -- rural bodies: their block
    census_code      VARCHAR(10)  NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_local_bodies_state FOREIGN KEY (state_code) REFERENCES geo_states (state_code),
    CONSTRAINT fk_local_bodies_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    CONSTRAINT fk_local_bodies_block FOREIGN KEY (block_code) REFERENCES geo_blocks (block_code),
    CONSTRAINT chk_local_bodies_area_kind CHECK (area_kind IN ('RURAL', 'URBAN')),
    KEY idx_local_bodies_district (district_code, area_kind, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='LGD Gram Panchayats and urban local bodies';

CREATE TABLE geo_villages (
    village_code     INT UNSIGNED NOT NULL PRIMARY KEY,  -- LGD village code (e.g. 254810 = Jolah Bigha, Tekari)
    name             VARCHAR(150) NOT NULL,
    -- lower-case, punctuation/space-free copy of name used for matching
    -- Google's locality names ("Jolhabigha" == "Jolah Bigha"); written by
    -- the importer and by AdminAreaService - see GeoNames.normalize().
    name_key         VARCHAR(150) NOT NULL,
    state_code       INT UNSIGNED NOT NULL,
    district_code    INT UNSIGNED NOT NULL,
    subdistrict_code INT UNSIGNED NOT NULL,
    block_code       INT UNSIGNED NULL,
    census_code      VARCHAR(10)  NULL,
    -- optional centre point (from a village boundary file or set by Admin);
    -- lets the map open on the village in the manual flow.
    centre_latitude  DECIMAL(9,6) NULL,
    centre_longitude DECIMAL(9,6) NULL,
    CONSTRAINT fk_geo_villages_state FOREIGN KEY (state_code) REFERENCES geo_states (state_code),
    CONSTRAINT fk_geo_villages_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    CONSTRAINT fk_geo_villages_subdistrict FOREIGN KEY (subdistrict_code) REFERENCES geo_subdistricts (subdistrict_code),
    CONSTRAINT fk_geo_villages_block FOREIGN KEY (block_code) REFERENCES geo_blocks (block_code),
    KEY idx_geo_villages_district_key (district_code, name_key),
    KEY idx_geo_villages_subdistrict (subdistrict_code, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='LGD revenue villages. Hamlets (tola) are not listed by LGD - they belong to a revenue village';

-- Village -> Gram Panchayat (rural) or urban body (village absorbed into a
-- city). Usually one row per village; LGD lists a few villages under two
-- Gram Panchayats, which is why this is a separate table.
CREATE TABLE village_local_bodies (
    village_code     INT UNSIGNED NOT NULL,
    local_body_code  INT UNSIGNED NOT NULL,
    PRIMARY KEY (village_code, local_body_code),
    KEY idx_village_local_bodies_body (local_body_code),
    CONSTRAINT fk_village_local_bodies_village FOREIGN KEY (village_code) REFERENCES geo_villages (village_code)
        ON DELETE CASCADE,
    CONSTRAINT fk_village_local_bodies_body FOREIGN KEY (local_body_code) REFERENCES local_bodies (local_body_code)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Which Gram Panchayat / urban body covers each village';

-- ---------------------------------------------------------------------------
-- 2. PIN codes
-- ---------------------------------------------------------------------------

-- PIN -> villages (from the LGD "villages with PIN code" list when loaded).
CREATE TABLE village_pincodes (
    village_code  INT UNSIGNED NOT NULL,
    pincode       CHAR(6)      NOT NULL,
    PRIMARY KEY (village_code, pincode),
    KEY idx_village_pincodes_pincode (pincode),
    CONSTRAINT fk_village_pincodes_village FOREIGN KEY (village_code) REFERENCES geo_villages (village_code)
        ON DELETE CASCADE,
    CONSTRAINT chk_village_pincodes_pincode CHECK (pincode REGEXP '^[1-9][0-9]{5}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Which villages a PIN code serves';

-- India Post offices: PIN -> district (+ office location). Used when a PIN
-- has no village_pincodes rows yet: the app then lists the villages of the
-- PIN's district/sub-district instead (AdminAreaService.villagesForPincode).
CREATE TABLE pincode_offices (
    office_id      BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    pincode        CHAR(6)      NOT NULL,
    office_name    VARCHAR(150) NOT NULL,
    office_type    VARCHAR(10)  NULL,       -- BO / PO / HO
    district_code  INT UNSIGNED NULL,       -- matched to LGD by the importer, NULL if unmatched
    district_name  VARCHAR(100) NOT NULL,   -- as written by India Post
    state_name     VARCHAR(100) NOT NULL,
    latitude       DECIMAL(9,6) NULL,
    longitude      DECIMAL(9,6) NULL,
    UNIQUE KEY uq_pincode_offices (pincode, office_name),
    CONSTRAINT fk_pincode_offices_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    CONSTRAINT chk_pincode_offices_pincode CHECK (pincode REGEXP '^[1-9][0-9]{5}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='India Post offices per PIN code';

-- ---------------------------------------------------------------------------
-- 3. Wards belong to a local body
-- ---------------------------------------------------------------------------

ALTER TABLE wards
    ADD COLUMN local_body_code  INT UNSIGNED NULL AFTER code,
    ADD COLUMN lgd_ward_code    INT UNSIGNED NULL AFTER local_body_code,
    ADD COLUMN ward_number      VARCHAR(20)  NULL AFTER lgd_ward_code,
    -- centre for the map when no boundary is on record (Admin or importer)
    ADD COLUMN centre_latitude  DECIMAL(9,6) NULL AFTER boundary_geojson,
    ADD COLUMN centre_longitude DECIMAL(9,6) NULL AFTER centre_latitude,
    ADD CONSTRAINT fk_wards_local_body FOREIGN KEY (local_body_code) REFERENCES local_bodies (local_body_code),
    ADD UNIQUE KEY uq_wards_lgd_ward_code (lgd_ward_code);

-- "Ward No. 1" exists in every body: names are unique per body, not globally.
-- Legacy wards (local_body_code NULL) keep their already-unique names.
ALTER TABLE wards DROP INDEX uq_wards_name;
ALTER TABLE wards ADD UNIQUE KEY uq_wards_body_name (local_body_code, name);
CREATE INDEX idx_wards_local_body_active ON wards (local_body_code, is_active);

-- Urban colony / mohalla / sector -> ward (from the ward notification).
-- AreaResolver matches Google's sublocality/neighbourhood name against
-- locality_key to pick the ward when the ward has no boundary.
CREATE TABLE ward_localities (
    ward_locality_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    ward_id          BIGINT UNSIGNED NOT NULL,
    locality_name    VARCHAR(150)    NOT NULL,
    locality_key     VARCHAR(150)    NOT NULL,   -- GeoNames.normalize(locality_name)
    village_code     INT UNSIGNED    NULL,       -- set when the locality is an LGD village
    CONSTRAINT fk_ward_localities_ward FOREIGN KEY (ward_id) REFERENCES wards (ward_id) ON DELETE CASCADE,
    CONSTRAINT fk_ward_localities_village FOREIGN KEY (village_code) REFERENCES geo_villages (village_code),
    UNIQUE KEY uq_ward_localities (ward_id, locality_key),
    KEY idx_ward_localities_key (locality_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Colonies / mohallas / villages listed in each ward';

-- ---------------------------------------------------------------------------
-- 4. Complaint location: full address + resolved area codes
-- ---------------------------------------------------------------------------

ALTER TABLE locations
    ADD COLUMN pincode          CHAR(6)      NULL AFTER formatted_address,
    ADD COLUMN landmark         VARCHAR(150) NULL AFTER pincode,   -- e.g. "Republic Crossing" (Google place name)
    ADD COLUMN locality         VARCHAR(150) NULL AFTER landmark,  -- colony / sector / mohalla from Google
    ADD COLUMN state_code       INT UNSIGNED NULL AFTER locality,
    ADD COLUMN district_code    INT UNSIGNED NULL AFTER state_code,
    ADD COLUMN local_body_code  INT UNSIGNED NULL AFTER district_code,
    ADD COLUMN village_code     INT UNSIGNED NULL AFTER local_body_code,
    -- how ward_id was decided: BOUNDARY (point in ward polygon), LOCALITY
    -- (ward_localities match), CITIZEN (picked from the body's ward list),
    -- NEAREST (nearest boundary), LEGACY (V33 and older)
    ADD COLUMN ward_match       VARCHAR(12)  NULL AFTER village_code,
    ADD CONSTRAINT fk_locations_state FOREIGN KEY (state_code) REFERENCES geo_states (state_code),
    ADD CONSTRAINT fk_locations_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    ADD CONSTRAINT fk_locations_local_body FOREIGN KEY (local_body_code) REFERENCES local_bodies (local_body_code),
    ADD CONSTRAINT fk_locations_village FOREIGN KEY (village_code) REFERENCES geo_villages (village_code),
    ADD CONSTRAINT chk_locations_pincode CHECK (pincode IS NULL OR pincode REGEXP '^[1-9][0-9]{5}$'),
    ADD CONSTRAINT chk_locations_ward_match CHECK (
        ward_match IS NULL OR ward_match IN ('BOUNDARY', 'LOCALITY', 'CITIZEN', 'NEAREST', 'LEGACY')
    );
CREATE INDEX idx_locations_local_body ON locations (local_body_code);
CREATE INDEX idx_locations_district ON locations (district_code);

-- ---------------------------------------------------------------------------
-- 5. Staff and department jurisdiction
-- ---------------------------------------------------------------------------

-- Officers / department heads work for one local body (and optionally one
-- ward of it, users.ward_id). Set by Admin through the same location picker
-- the citizen uses (AdminUserService). NULL = not limited (legacy accounts,
-- Admins).
ALTER TABLE users
    ADD COLUMN district_code   INT UNSIGNED NULL AFTER ward_id,
    ADD COLUMN local_body_code INT UNSIGNED NULL AFTER district_code,
    ADD CONSTRAINT fk_users_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    ADD CONSTRAINT fk_users_local_body FOREIGN KEY (local_body_code) REFERENCES local_bodies (local_body_code);
CREATE INDEX idx_users_local_body ON users (local_body_code, role);

-- A department serves a whole district or one local body; both NULL =
-- serves every area (the existing global departments). Routing
-- (DepartmentAssignmentService) prefers the most specific department.
ALTER TABLE departments
    ADD COLUMN district_code   INT UNSIGNED NULL AFTER description,
    ADD COLUMN local_body_code INT UNSIGNED NULL AFTER district_code,
    ADD CONSTRAINT fk_departments_district FOREIGN KEY (district_code) REFERENCES geo_districts (district_code),
    ADD CONSTRAINT fk_departments_local_body FOREIGN KEY (local_body_code) REFERENCES local_bodies (local_body_code),
    -- one "Public Works" per area: uniqueness is (name, area), not name alone
    ADD COLUMN scope_key VARCHAR(24) GENERATED ALWAYS AS (
        CASE
            WHEN local_body_code IS NOT NULL THEN CONCAT('LB', local_body_code)
            WHEN district_code IS NOT NULL THEN CONCAT('D', district_code)
            ELSE 'ALL'
        END) STORED;
ALTER TABLE departments DROP INDEX uq_departments_name;
ALTER TABLE departments ADD UNIQUE KEY uq_departments_name_scope (name, scope_key);

-- ---------------------------------------------------------------------------
-- 6. Where the loaded reference data came from (shown on the Admin screen)
-- ---------------------------------------------------------------------------

CREATE TABLE geo_data_imports (
    import_id     BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    dataset       VARCHAR(40)  NOT NULL,   -- VILLAGES, URBAN_BODIES, WARDS, PINCODES, VILLAGE_PINCODES ...
    source        VARCHAR(200) NOT NULL,   -- file name / URL
    source_date   DATE         NULL,       -- date the source was downloaded
    scope         VARCHAR(200) NULL,       -- e.g. "district 196 (GAYA)"
    row_count     INT UNSIGNED NOT NULL DEFAULT 0,
    imported_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='History of LGD / India Post data loads';
