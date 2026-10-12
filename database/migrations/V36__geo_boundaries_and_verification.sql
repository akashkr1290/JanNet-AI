-- V36__geo_boundaries_and_verification.sql
-- Location v2, part 2: our own geographic dataset (no map API key needed).
--
-- WHY: the spec "Intelligent Geographical Location Detection & Automatic
-- Complaint Routing" (2026-10-11) requires that
--   * a ward is assigned automatically ONLY when the point lies inside a
--     published ward boundary - never from an approximate address/name match;
--   * PIN codes and local bodies are many-to-many;
--   * every geographic record keeps its source and verification status;
--   * a complaint whose ward could not be proven goes to manual verification;
--   * assignments keep a history.
--
-- WHAT:
--   geo_boundaries        - polygons (ward / urban body / village / Gram
--                           Panchayat / PIN) in MySQL spatial SRID 4326 with a
--                           SPATIAL INDEX; point-in-polygon with ST_Contains.
--                           Read by service/geo/GeoRepository#containing.
--   pincode_local_bodies  - PIN <-> Gram Panchayat / urban body (many-to-many)
--                           with mapping source + verification status.
--   source / verification columns on wards, pincode_offices, village_pincodes.
--   locations.location_verification_status / verification_reason /
--   suggested_ward_id     - written by service/complaint/LocationService.
--   complaint_assignments - department / officer assignment history, written
--                           by DepartmentAssignmentService and
--                           ComplaintService#reassign.
--
-- DATA: this migration creates structure only. Rows come from
-- database/tools/geo_import (LGD daily dump, India Post directory, Swachh
-- Bharat Mission ward boundaries, Bharatmaps village/panchayat boundaries,
-- data.gov.in PIN boundaries) - see database/tools/geo_import/README.md.
--
-- Verification status values used everywhere below:
--   PUBLISHED  - published by a government portal (LGD, India Post, SBM,
--                Bharatmaps, data.gov.in), loaded unchanged, not yet checked
--                by our staff;
--   VERIFIED   - confirmed by an Admin / officer of this deployment;
--   UNVERIFIED - entered by hand or derived by us (e.g. boundary overlap) and
--                not yet confirmed.

-- ---------------------------------------------------------------------------
-- Boundary polygons
-- ---------------------------------------------------------------------------
CREATE TABLE geo_boundaries (
    boundary_id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    layer                VARCHAR(12)  NOT NULL,   -- WARD, LOCAL_BODY, VILLAGE, PANCHAYAT, PINCODE
    ref_code             VARCHAR(20)  NOT NULL,   -- wards.ward_id / LGD code / 6-digit PIN
    name                 VARCHAR(200) NULL,       -- name as written in the source (for checking)
    geom                 GEOMETRY     NOT NULL SRID 4326,
    source               VARCHAR(30)  NOT NULL,   -- SBM, BHARATMAPS_LGD, DATAGOV_PIN, ADMIN
    source_ref           VARCHAR(80)  NOT NULL,   -- feature id in the source file
    source_date          DATE         NULL,       -- date the source was downloaded
    verification_status  VARCHAR(12)  NOT NULL DEFAULT 'PUBLISHED',
    import_id            BIGINT UNSIGNED NULL,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_geo_boundaries_source (source, layer, source_ref),
    KEY idx_geo_boundaries_ref (layer, ref_code),
    SPATIAL INDEX sp_geo_boundaries_geom (geom),
    CONSTRAINT fk_geo_boundaries_import FOREIGN KEY (import_id) REFERENCES geo_data_imports (import_id)
        ON DELETE SET NULL,
    CONSTRAINT chk_geo_boundaries_layer CHECK (layer IN ('WARD', 'LOCAL_BODY', 'VILLAGE', 'PANCHAYAT', 'PINCODE')),
    CONSTRAINT chk_geo_boundaries_status CHECK (verification_status IN ('PUBLISHED', 'VERIFIED', 'UNVERIFIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Boundary polygons (SRID 4326) for point-in-polygon area and ward detection';

-- ---------------------------------------------------------------------------
-- PIN <-> local body (many-to-many)
-- ---------------------------------------------------------------------------
CREATE TABLE pincode_local_bodies (
    pincode              CHAR(6)      NOT NULL,
    local_body_code      INT UNSIGNED NOT NULL,
    mapping_source       VARCHAR(30)  NOT NULL,   -- LGD_VILLAGE_PIN, LGD_URBAN_PIN, BOUNDARY_OVERLAP, ADMIN
    verification_status  VARCHAR(12)  NOT NULL DEFAULT 'PUBLISHED',
    village_count        INT UNSIGNED NOT NULL DEFAULT 0,  -- villages of the body under this PIN (LGD_VILLAGE_PIN)
    import_id            BIGINT UNSIGNED NULL,
    PRIMARY KEY (pincode, local_body_code, mapping_source),
    KEY idx_pincode_local_bodies_body (local_body_code),
    CONSTRAINT fk_pincode_local_bodies_body FOREIGN KEY (local_body_code) REFERENCES local_bodies (local_body_code)
        ON DELETE CASCADE,
    CONSTRAINT fk_pincode_local_bodies_import FOREIGN KEY (import_id) REFERENCES geo_data_imports (import_id)
        ON DELETE SET NULL,
    CONSTRAINT chk_pincode_local_bodies_pincode CHECK (pincode REGEXP '^[1-9][0-9]{5}$'),
    CONSTRAINT chk_pincode_local_bodies_status CHECK (verification_status IN ('PUBLISHED', 'VERIFIED', 'UNVERIFIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Which Gram Panchayats / urban bodies a PIN code covers (and the reverse)';

-- ---------------------------------------------------------------------------
-- Source + verification on existing geo tables
-- ---------------------------------------------------------------------------
ALTER TABLE wards
    ADD COLUMN source              VARCHAR(30) NOT NULL DEFAULT 'ADMIN' AFTER ward_number,
    ADD COLUMN source_ref          VARCHAR(80) NULL AFTER source,
    ADD COLUMN source_date         DATE        NULL AFTER source_ref,
    ADD COLUMN verification_status VARCHAR(12) NOT NULL DEFAULT 'UNVERIFIED' AFTER source_date,
    ADD UNIQUE KEY uq_wards_source_ref (source, source_ref),
    ADD CONSTRAINT chk_wards_verification_status CHECK (verification_status IN ('PUBLISHED', 'VERIFIED', 'UNVERIFIED'));
-- A body can have ward lists from two sources with different delimitations
-- (e.g. Ghaziabad: LGD lists 60 old wards, Swachh Bharat Mission maps 100
-- current ones). Names only need to be unique within one source; the importer
-- keeps one source active per body (database/tools/geo_import).
ALTER TABLE wards DROP INDEX uq_wards_body_name;
ALTER TABLE wards ADD UNIQUE KEY uq_wards_body_source_name (local_body_code, source, name);

ALTER TABLE pincode_offices
    ADD COLUMN delivery     VARCHAR(20) NULL AFTER office_type,
    ADD COLUMN division_name VARCHAR(100) NULL AFTER delivery,
    ADD COLUMN source       VARCHAR(30) NOT NULL DEFAULT 'INDIA_POST' AFTER longitude,
    ADD COLUMN source_date  DATE        NULL AFTER source;
CREATE INDEX idx_pincode_offices_district ON pincode_offices (district_code);

ALTER TABLE village_pincodes
    ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT 'LGD' AFTER pincode;

-- ---------------------------------------------------------------------------
-- Complaint location verification
-- ---------------------------------------------------------------------------
ALTER TABLE locations
    ADD COLUMN location_verification_status VARCHAR(24) NULL AFTER ward_match,
    ADD COLUMN verification_reason          VARCHAR(40) NULL AFTER location_verification_status,
    ADD COLUMN suggested_ward_id            BIGINT UNSIGNED NULL AFTER verification_reason,
    ADD CONSTRAINT fk_locations_suggested_ward FOREIGN KEY (suggested_ward_id) REFERENCES wards (ward_id)
        ON DELETE SET NULL,
    ADD CONSTRAINT chk_locations_verification_status CHECK (
        location_verification_status IS NULL
        OR location_verification_status IN ('VERIFIED_BOUNDARY', 'REQUIRES_VERIFICATION', 'STAFF_VERIFIED')
    );
-- STAFF: ward set by an officer in the verification queue
ALTER TABLE locations DROP CHECK chk_locations_ward_match;
ALTER TABLE locations ADD CONSTRAINT chk_locations_ward_match CHECK (
    ward_match IS NULL OR ward_match IN ('BOUNDARY', 'LOCALITY', 'CITIZEN', 'NEAREST', 'LEGACY', 'STAFF')
);
CREATE INDEX idx_locations_verification ON locations (location_verification_status);

-- ---------------------------------------------------------------------------
-- Assignment history
-- ---------------------------------------------------------------------------
CREATE TABLE complaint_assignments (
    assignment_id    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    complaint_id     BIGINT UNSIGNED NOT NULL,
    department_id    BIGINT UNSIGNED NULL,
    officer_id       BIGINT UNSIGNED NULL,
    local_body_code  INT UNSIGNED    NULL,     -- area the routing used (NULL = global department)
    ward_id          BIGINT UNSIGNED NULL,
    routing_rule_id  BIGINT UNSIGNED NULL,
    method           VARCHAR(20)     NOT NULL, -- RULE, AREA_RULE, FALLBACK, MANUAL
    reason           VARCHAR(300)    NULL,
    assigned_by      BIGINT UNSIGNED NULL,     -- NULL = system
    assigned_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_complaint_assignments_complaint (complaint_id, assigned_at),
    CONSTRAINT fk_complaint_assignments_complaint FOREIGN KEY (complaint_id) REFERENCES complaints (complaint_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_complaint_assignments_department FOREIGN KEY (department_id) REFERENCES departments (department_id),
    CONSTRAINT fk_complaint_assignments_officer FOREIGN KEY (officer_id) REFERENCES users (user_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_complaint_assignments_body FOREIGN KEY (local_body_code) REFERENCES local_bodies (local_body_code),
    CONSTRAINT fk_complaint_assignments_ward FOREIGN KEY (ward_id) REFERENCES wards (ward_id) ON DELETE SET NULL,
    CONSTRAINT fk_complaint_assignments_rule FOREIGN KEY (routing_rule_id) REFERENCES routing_rules (routing_rule_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_complaint_assignments_by FOREIGN KEY (assigned_by) REFERENCES users (user_id) ON DELETE SET NULL,
    CONSTRAINT chk_complaint_assignments_method CHECK (method IN ('RULE', 'AREA_RULE', 'FALLBACK', 'MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Department / officer assignment history of each complaint';
