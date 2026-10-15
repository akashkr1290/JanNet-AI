-- V37__block_level_routing.sql
-- Location v2, part 3: development block (prakhand) as a routing level.
--
-- WHY: a citizen drops a pin in a village; the complaint must reach the
-- department of that village's block (UP + Bihar pilot, plan
-- plans/2026-10-14_UP_BIHAR_LOCATION_ACCURACY_PLAN.md, WP2). Until now a
-- department served a whole district or one local body only, and a
-- complaint's location kept no block.
--
-- WHAT:
--   departments.block_code - the department serves one block. district_code
--                            stays set (the block's district) so district
--                            lists keep working; local_body_code stays NULL.
--   departments.scope_key  - adds 'B<block_code>' so one "Public Works" per
--                            block is allowed (uq_departments_name_scope).
--                            Most specific first: LB > B > D > ALL.
--   locations.block_code   - the block resolved for the complaint's point,
--                            written by service/complaint/LocationService.
--
-- DATA: blocks come from LGD (geo_blocks); villages and Gram Panchayats get
-- their block from LGD villages_by_blocks via database/tools/geo_import.
-- No BLOCK boundary layer: the block is taken from the village / Gram
-- Panchayat that contains the point (no block map with a clear source and
-- licence was found).
--
-- ROUTING ORDER (DepartmentAssignmentService, WP3):
--   local body (Gram Panchayat / town) -> block -> district -> every area.
-- Existing rows are unchanged: every current department has block_code NULL.

ALTER TABLE departments
    ADD COLUMN block_code INT UNSIGNED NULL AFTER district_code,
    ADD CONSTRAINT fk_departments_block FOREIGN KEY (block_code) REFERENCES geo_blocks (block_code),
    -- a block department also names its district; a local-body department
    -- does not name a block (one area per department)
    ADD CONSTRAINT chk_departments_block_scope CHECK (
        block_code IS NULL OR (district_code IS NOT NULL AND local_body_code IS NULL)
    );

ALTER TABLE departments
    MODIFY COLUMN scope_key VARCHAR(24) GENERATED ALWAYS AS (
        CASE
            WHEN local_body_code IS NOT NULL THEN CONCAT('LB', local_body_code)
            WHEN block_code IS NOT NULL THEN CONCAT('B', block_code)
            WHEN district_code IS NOT NULL THEN CONCAT('D', district_code)
            ELSE 'ALL'
        END) STORED;

ALTER TABLE locations
    ADD COLUMN block_code INT UNSIGNED NULL AFTER local_body_code,
    ADD CONSTRAINT fk_locations_block FOREIGN KEY (block_code) REFERENCES geo_blocks (block_code);
