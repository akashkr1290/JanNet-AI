package com.jannetai.backend.service.geo;

import com.jannetai.backend.dto.geo.AreaRef;
import com.jannetai.backend.dto.geo.BlockRef;
import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.LocalBodyRef;
import com.jannetai.backend.dto.geo.PincodeBodyRef;
import com.jannetai.backend.dto.geo.PincodeSuggestion;
import com.jannetai.backend.dto.geo.PlaceResult;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.dto.geo.WardRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read access to the V34 LGD tables (geo_*, local_bodies, village_*,
 * pincode_offices, wards by body). Plain JDBC rather than JPA entities: these
 * are read-only reference lists queried in bulk (a district has ~3,000
 * villages) and mapped straight to the dto/geo records the API returns.
 *
 * <p>Used by {@link AreaResolver} (matching) and {@link AdminAreaService}
 * (lists for the app and Admin screens). A district's village list is
 * cached for {@link #CACHE_TTL_MS}; {@link #evictCaches()} is called after an
 * Admin change (AdminAreaService) so edits show at once.
 */
@Repository
public class GeoRepository {

    static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<Integer, Cached<List<VillageRef>>> villagesByDistrict = new ConcurrentHashMap<>();
    private volatile Cached<List<AreaRef>> states;

    private record Cached<T>(T value, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < CACHE_TTL_MS;
        }
    }

    public GeoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void evictCaches() {
        villagesByDistrict.clear();
        states = null;
    }

    // ---------------------------------------------------------------- states / districts

    public List<AreaRef> states() {
        Cached<List<AreaRef>> c = states;
        if (c == null || !c.fresh()) {
            c = new Cached<>(jdbc.query("SELECT state_code, name FROM geo_states ORDER BY name",
                    (rs, i) -> new AreaRef(rs.getInt(1), rs.getString(2))), System.currentTimeMillis());
            states = c;
        }
        return c.value();
    }

    private static final String DISTRICT_SQL = "SELECT d.district_code, d.name, d.state_code, s.name AS state_name, d.is_served "
            + "FROM geo_districts d JOIN geo_states s ON s.state_code = d.state_code ";

    public List<DistrictRef> districts(Integer stateCode) {
        return stateCode == null
                ? jdbc.query(DISTRICT_SQL + "WHERE d.is_served = TRUE ORDER BY d.name", GeoRepository::district)
                : jdbc.query(DISTRICT_SQL + "WHERE d.state_code = :s ORDER BY d.name", Map.of("s", stateCode),
                GeoRepository::district);
    }

    public Optional<DistrictRef> district(int districtCode) {
        return jdbc.query(DISTRICT_SQL + "WHERE d.district_code = :d", Map.of("d", districtCode), GeoRepository::district)
                .stream().findFirst();
    }

    /** True when at least one district is served - then is_served decides jurisdiction (LocationService). */
    public boolean anyDistrictServed() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM geo_districts WHERE is_served = TRUE", Map.of(), Integer.class);
        return n != null && n > 0;
    }

    public int setDistrictServed(int districtCode, boolean served) {
        return jdbc.update("UPDATE geo_districts SET is_served = :v WHERE district_code = :d",
                Map.of("v", served, "d", districtCode));
    }

    private static DistrictRef district(ResultSet rs, int i) throws SQLException {
        return new DistrictRef(rs.getInt("district_code"), rs.getString("name"), rs.getInt("state_code"),
                rs.getString("state_name"), rs.getBoolean("is_served"));
    }

    // ---------------------------------------------------------------- local bodies

    private static final String BODY_SQL = "SELECT local_body_code, name, area_kind, body_type, district_code FROM local_bodies ";

    public List<LocalBodyRef> localBodies(int districtCode, String kind) {
        MapSqlParameterSource p = new MapSqlParameterSource("d", districtCode);
        String sql = BODY_SQL + "WHERE district_code = :d AND is_active = TRUE";
        if (kind != null) {
            sql += " AND area_kind = :k";
            p.addValue("k", kind);
        }
        return jdbc.query(sql + " ORDER BY name", p, GeoRepository::body);
    }

    public Optional<LocalBodyRef> localBody(int code) {
        return jdbc.query(BODY_SQL + "WHERE local_body_code = :c", Map.of("c", code), GeoRepository::body)
                .stream().findFirst();
    }

    private static LocalBodyRef body(ResultSet rs, int i) throws SQLException {
        return new LocalBodyRef(rs.getInt("local_body_code"), rs.getString("name"), rs.getString("area_kind"),
                rs.getString("body_type"), rs.getObject("district_code", Integer.class));
    }

    // ---------------------------------------------------------------- blocks (V37)

    private static final String BLOCK_SQL = "SELECT b.block_code, b.name, b.district_code FROM geo_blocks b ";

    /** Development blocks of a district, by name - the Admin department picker. */
    public List<BlockRef> blocks(int districtCode) {
        return jdbc.query(BLOCK_SQL + "WHERE b.district_code = :d ORDER BY b.name", Map.of("d", districtCode),
                GeoRepository::block);
    }

    public Optional<BlockRef> block(int blockCode) {
        return jdbc.query(BLOCK_SQL + "WHERE b.block_code = :b", Map.of("b", blockCode), GeoRepository::block)
                .stream().findFirst();
    }

    /** The block of a Gram Panchayat (local_bodies.block_code, set by geo_import); empty for towns. */
    public Optional<BlockRef> blockOfBody(int localBodyCode) {
        return jdbc.query(BLOCK_SQL + "JOIN local_bodies lb ON lb.block_code = b.block_code WHERE lb.local_body_code = :c",
                Map.of("c", localBodyCode), GeoRepository::block).stream().findFirst();
    }

    private static BlockRef block(ResultSet rs, int i) throws SQLException {
        return new BlockRef(rs.getInt("block_code"), rs.getString("name"), rs.getInt("district_code"));
    }

    // ---------------------------------------------------------------- villages

    /**
     * Every village of a district with its tehsil, block and local bodies -
     * the list AreaResolver ranks Google's names against. Cached.
     */
    public List<VillageRef> villagesOfDistrict(int districtCode) {
        Cached<List<VillageRef>> c = villagesByDistrict.get(districtCode);
        if (c != null && c.fresh()) {
            return c.value();
        }
        List<VillageRef> list = loadVillages("v.district_code = :d", new MapSqlParameterSource("d", districtCode));
        villagesByDistrict.put(districtCode, new Cached<>(list, System.currentTimeMillis()));
        return list;
    }

    public List<VillageRef> villagesByCodes(Collection<Integer> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return loadVillages("v.village_code IN (:c)", new MapSqlParameterSource("c", codes));
    }

    public Optional<VillageRef> village(int code) {
        return villagesByCodes(List.of(code)).stream().findFirst();
    }

    private List<VillageRef> loadVillages(String where, MapSqlParameterSource params) {
        String sql = "SELECT v.village_code, v.name, v.district_code, v.centre_latitude, v.centre_longitude, "
                + "s.name AS sub_name, v.block_code, b.name AS block_name, "
                + "lb.local_body_code, lb.name AS body_name, lb.area_kind, lb.body_type, lb.district_code AS body_district "
                + "FROM geo_villages v "
                + "JOIN geo_subdistricts s ON s.subdistrict_code = v.subdistrict_code "
                + "LEFT JOIN geo_blocks b ON b.block_code = v.block_code "
                + "LEFT JOIN village_local_bodies vl ON vl.village_code = v.village_code "
                + "LEFT JOIN local_bodies lb ON lb.local_body_code = vl.local_body_code AND lb.is_active = TRUE "
                + "WHERE " + where + " ORDER BY v.name, v.village_code";
        Map<Integer, VillageRow> rows = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            int code = rs.getInt("village_code");
            VillageRow row = rows.computeIfAbsent(code, k -> new VillageRow());
            if (row.name == null) {
                row.name = rs.getString("name");
                row.district = rs.getInt("district_code");
                row.sub = rs.getString("sub_name");
                row.block = rs.getString("block_name");
                row.blockCode = rs.getObject("block_code", Integer.class);
                row.lat = rs.getBigDecimal("centre_latitude");
                row.lng = rs.getBigDecimal("centre_longitude");
            }
            Integer body = rs.getObject("local_body_code", Integer.class);
            if (body != null) {
                row.bodies.add(new LocalBodyRef(body, rs.getString("body_name"), rs.getString("area_kind"),
                        rs.getString("body_type"), rs.getObject("body_district", Integer.class)));
            }
        });
        List<VillageRef> out = new ArrayList<>(rows.size());
        rows.forEach((code, r) -> out.add(new VillageRef(code, r.name, r.district, r.sub, r.block,
                List.copyOf(r.bodies), r.lat, r.lng, r.blockCode)));
        return out;
    }

    private static final class VillageRow {
        String name;
        int district;
        String sub;
        String block;
        Integer blockCode;
        BigDecimal lat;
        BigDecimal lng;
        final List<LocalBodyRef> bodies = new ArrayList<>();
    }

    /** Villages a local body covers (manual flow: a Gram Panchayat's villages). */
    public List<VillageRef> villagesOfBody(int bodyCode) {
        return loadVillages("v.village_code IN (SELECT village_code FROM village_local_bodies WHERE local_body_code = :b)",
                new MapSqlParameterSource("b", bodyCode));
    }

    public int setVillageCentre(int villageCode, BigDecimal lat, BigDecimal lng) {
        return jdbc.update("UPDATE geo_villages SET centre_latitude = :lat, centre_longitude = :lng WHERE village_code = :v",
                new MapSqlParameterSource("v", villageCode).addValue("lat", lat).addValue("lng", lng));
    }

    // ---------------------------------------------------------------- PIN codes

    public List<Integer> villageCodesForPincode(String pincode) {
        return jdbc.queryForList("SELECT village_code FROM village_pincodes WHERE pincode = :p",
                Map.of("p", pincode), Integer.class);
    }

    /** India Post offices of a PIN (name, matched district, location). */
    public List<PostOffice> postOffices(String pincode) {
        return jdbc.query("SELECT office_name, district_code, latitude, longitude FROM pincode_offices WHERE pincode = :p "
                        + "ORDER BY office_type = 'BO', office_name",
                Map.of("p", pincode), (rs, i) -> new PostOffice(rs.getString("office_name"),
                        rs.getObject("district_code", Integer.class), rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude")));
    }

    public record PostOffice(String name, Integer districtCode, BigDecimal latitude, BigDecimal longitude) {
    }

    /**
     * Nearest post office (with a known district) to a point, within about
     * 15 km - AreaResolver's fallback for the district and PIN when Google
     * is unavailable. Returns the office's PIN too.
     */
    public Optional<NearOffice> nearestPostOffice(double lat, double lng) {
        double d = 0.15; // degrees, ~16 km north-south
        MapSqlParameterSource p = new MapSqlParameterSource("lat", lat).addValue("lng", lng)
                .addValue("lat1", lat - d).addValue("lat2", lat + d).addValue("lng1", lng - d).addValue("lng2", lng + d);
        return jdbc.query("SELECT pincode, office_name, district_code FROM pincode_offices "
                        + "WHERE latitude BETWEEN :lat1 AND :lat2 AND longitude BETWEEN :lng1 AND :lng2 AND district_code IS NOT NULL "
                        + "ORDER BY POW(latitude - :lat, 2) + POW((longitude - :lng) * COS(RADIANS(:lat)), 2) LIMIT 1",
                p, (rs, i) -> new NearOffice(rs.getString(1), rs.getString(2), rs.getInt(3))).stream().findFirst();
    }

    public record NearOffice(String pincode, String officeName, int districtCode) {
    }

    // ---------------------------------------------------------------- wards

    /** Active wards of a body with their listed localities (ward picker). */
    public List<WardRef> wardsOfBody(int bodyCode) {
        Map<Long, List<String>> localities = new LinkedHashMap<>();
        jdbc.query("SELECT wl.ward_id, wl.locality_name FROM ward_localities wl JOIN wards w ON w.ward_id = wl.ward_id "
                        + "WHERE w.local_body_code = :b ORDER BY wl.locality_name",
                Map.of("b", bodyCode), rs -> {
                    localities.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return jdbc.query(WARD_SQL + "WHERE w.local_body_code = :b AND w.is_active = TRUE "
                        + "ORDER BY CAST(w.ward_number AS UNSIGNED), w.ward_number, w.name",
                Map.of("b", bodyCode), (rs, i) -> ward(rs, localities));
    }

    /** One active ward (any body), e.g. the ward a boundary hit points to. */
    public Optional<WardRef> activeWard(long wardId) {
        return jdbc.query(WARD_SQL + "WHERE w.ward_id = :w AND w.is_active = TRUE", Map.of("w", wardId),
                (rs, i) -> ward(rs, Map.of())).stream().findFirst();
    }

    /** V36: a ward "has a boundary" when an Admin drew one or a published ward map was loaded (geo_boundaries). */
    private static final String WARD_SQL = "SELECT w.ward_id, w.name, w.ward_number, w.local_body_code, w.source, "
            + "w.verification_status, w.centre_latitude, w.centre_longitude, "
            + "(w.boundary_geojson IS NOT NULL OR EXISTS (SELECT 1 FROM geo_boundaries b WHERE b.layer = 'WARD' "
            + "AND b.ref_code = CAST(w.ward_id AS CHAR))) AS has_boundary FROM wards w ";

    private static WardRef ward(ResultSet rs, Map<Long, List<String>> localities) throws SQLException {
        long id = rs.getLong("ward_id");
        return new WardRef(id, rs.getString("name"), rs.getString("ward_number"),
                rs.getObject("local_body_code", Integer.class), rs.getBoolean("has_boundary"),
                rs.getBigDecimal("centre_latitude"), rs.getBigDecimal("centre_longitude"),
                List.copyOf(localities.getOrDefault(id, List.of())), rs.getString("source"),
                rs.getString("verification_status"));
    }

    /** Boundaries of a body's active wards, for point-in-polygon (WardLocator). */
    public List<WardBoundary> wardBoundariesOfBody(int bodyCode) {
        return jdbc.query("SELECT ward_id, boundary_geojson FROM wards WHERE local_body_code = :b AND is_active = TRUE "
                        + "AND boundary_geojson IS NOT NULL",
                Map.of("b", bodyCode), (rs, i) -> new WardBoundary(rs.getLong(1), rs.getString(2)));
    }

    public record WardBoundary(long wardId, String geojson) {
    }

    /** Locality keys of a body's wards: locality_key -> ward ids (a colony can span two wards). */
    public Map<String, List<Long>> wardLocalityKeys(int bodyCode) {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        jdbc.query("SELECT wl.locality_key, wl.ward_id FROM ward_localities wl JOIN wards w ON w.ward_id = wl.ward_id "
                        + "WHERE w.local_body_code = :b AND w.is_active = TRUE",
                Map.of("b", bodyCode), rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getLong(2));
                });
        return out;
    }

    public int addWardLocality(long wardId, String name, Integer villageCode) {
        return jdbc.update("INSERT IGNORE INTO ward_localities (ward_id, locality_name, locality_key, village_code) "
                        + "VALUES (:w, :n, :k, :v)",
                new MapSqlParameterSource("w", wardId).addValue("n", name.trim()).addValue("k", GeoNames.normalize(name))
                        .addValue("v", villageCode));
    }

    public int removeWardLocality(long wardId, String name) {
        return jdbc.update("DELETE FROM ward_localities WHERE ward_id = :w AND locality_key = :k",
                Map.of("w", wardId, "k", GeoNames.normalize(name)));
    }

    // ---------------------------------------------------------------- V36: boundaries (MySQL spatial)

    /**
     * One polygon of geo_boundaries that contains a point.
     *
     * @param layer  WARD (refCode = wards.ward_id), LOCAL_BODY / PANCHAYAT
     *               (LGD body code), VILLAGE (LGD village code), PINCODE (PIN)
     * @param status PUBLISHED / VERIFIED / UNVERIFIED
     */
    public record BoundaryHit(String layer, String refCode, String status) {
    }

    /**
     * Every boundary polygon containing the point - ST_Contains on SRID 4326
     * with the SPATIAL INDEX of V36. Coordinates are passed long-lat explicitly
     * (MySQL's default axis order for 4326 is lat-long). A database error (e.g.
     * spatial data the server rejects) is logged and treated as "no boundary",
     * so the caller falls back to the lists - never to a guess.
     */
    public List<BoundaryHit> containing(double lat, double lng) {
        String point = String.format(java.util.Locale.ROOT, "POINT(%.7f %.7f)", lng, lat);
        try {
            return jdbc.query("SELECT layer, ref_code, verification_status FROM geo_boundaries "
                            + "WHERE ST_Contains(geom, ST_GeomFromText(:p, 4326, 'axis-order=long-lat'))",
                    Map.of("p", point), (rs, i) -> new BoundaryHit(rs.getString(1), rs.getString(2), rs.getString(3)));
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Boundary lookup failed for a point: {}", e.getMostSpecificCause().getMessage());
            return List.of();
        }
    }

    /**
     * GeoJSON geometry (lon, lat) of a ward: the published ward map, else an
     * Admin-drawn boundary. The app draws it as the ward outline on the map.
     */
    public Optional<String> wardBoundaryGeoJson(long wardId) {
        return jdbc.query("SELECT ST_AsGeoJSON(geom, 6) AS g FROM geo_boundaries WHERE layer = 'WARD' AND ref_code = :w "
                        + "UNION ALL SELECT CAST(boundary_geojson AS CHAR) FROM wards WHERE ward_id = :id "
                        + "AND boundary_geojson IS NOT NULL LIMIT 1",
                Map.of("w", String.valueOf(wardId), "id", wardId), (rs, i) -> rs.getString(1)).stream().findFirst();
    }

    /** Centre of a PIN's published boundary (largest polygon), or empty. */
    public Optional<double[]> pincodeCentre(String pincode) {
        return jdbc.query("SELECT ST_Y(ST_Centroid(ST_SRID(geom, 0))) AS lat, ST_X(ST_Centroid(ST_SRID(geom, 0))) AS lng "
                        + "FROM geo_boundaries WHERE layer = 'PINCODE' AND ref_code = :p "
                        + "ORDER BY ST_Area(ST_SRID(geom, 0)) DESC LIMIT 1",
                Map.of("p", pincode), (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)}).stream().findFirst();
    }

    // ---------------------------------------------------------------- V36: PIN <-> local bodies

    /**
     * Bodies LGD links to a PIN (many-to-many), towns first, then by number of
     * villages. One row per body; {@code mappingSource} lists every source that
     * links it (e.g. "LGD_URBAN_PIN,LGD_VILLAGE_PIN").
     */
    public List<PincodeBodyRef> pincodeBodies(String pincode) {
        return jdbc.query("SELECT lb.local_body_code, lb.name, lb.area_kind, lb.body_type, lb.district_code, "
                        + "GROUP_CONCAT(DISTINCT p.mapping_source ORDER BY p.mapping_source) AS mapping_source, "
                        + "CASE WHEN SUM(p.verification_status = 'VERIFIED') > 0 THEN 'VERIFIED' "
                        + "     WHEN SUM(p.verification_status = 'PUBLISHED') > 0 THEN 'PUBLISHED' ELSE 'UNVERIFIED' END "
                        + "  AS verification_status, MAX(p.village_count) AS village_count "
                        + "FROM pincode_local_bodies p "
                        + "JOIN local_bodies lb ON lb.local_body_code = p.local_body_code AND lb.is_active = TRUE "
                        + "WHERE p.pincode = :p GROUP BY lb.local_body_code, lb.name, lb.area_kind, lb.body_type, lb.district_code "
                        + "ORDER BY lb.area_kind = 'URBAN' DESC, village_count DESC, lb.name",
                Map.of("p", pincode), (rs, i) -> new PincodeBodyRef(body(rs, i), rs.getString("mapping_source"),
                        rs.getString("verification_status"), rs.getInt("village_count")));
    }

    /**
     * PINs starting with {@code prefix} that exist in the loaded data (post
     * offices or LGD village PINs), served districts first.
     */
    public List<PincodeSuggestion> pincodesStartingWith(String prefix, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("p", prefix + "%").addValue("n", limit);
        return jdbc.query("SELECT x.pincode, "
                        + "(SELECT o.office_name FROM pincode_offices o WHERE o.pincode = x.pincode "
                        + "  ORDER BY o.office_type = 'BO', o.office_name LIMIT 1) AS office, "
                        + "d.district_code, d.name AS district_name, s.name AS state_name, COALESCE(d.is_served, FALSE) AS served "
                        + "FROM (SELECT pincode, MIN(district_code) AS district_code FROM ("
                        + "   SELECT pincode, district_code FROM pincode_offices WHERE pincode LIKE :p "
                        + "   UNION ALL SELECT vp.pincode, v.district_code FROM village_pincodes vp "
                        + "   JOIN geo_villages v ON v.village_code = vp.village_code WHERE vp.pincode LIKE :p) u "
                        + " GROUP BY pincode) x "
                        + "LEFT JOIN geo_districts d ON d.district_code = x.district_code "
                        + "LEFT JOIN geo_states s ON s.state_code = d.state_code "
                        + "ORDER BY served DESC, x.pincode LIMIT :n",
                p, (rs, i) -> new PincodeSuggestion(rs.getString("pincode"), rs.getString("office"),
                        rs.getObject("district_code", Integer.class), rs.getString("district_name"),
                        rs.getString("state_name"), rs.getBoolean("served")));
    }

    // ---------------------------------------------------------------- V36: search in our own data

    /**
     * Names matching {@code query} in the served districts (all districts when
     * none is served): wards, post offices and villages. Only rows with a known
     * map position are returned.
     *
     * <p>"Rampur, Gaya" (or "Rampur Gaya" when the whole text finds nothing)
     * limits the search to districts whose name starts with the last part - a
     * common village name exists in dozens of districts.
     *
     * <p>Order (V37, see {@link #merge}): exact name matches first, then the
     * three kinds take turns (village, ward, post office), so villages are no
     * longer cut off when a name also matches many wards / post offices.
     */
    public List<PlaceResult> searchPlaces(String query, int limit) {
        String q = query.trim();
        int comma = q.indexOf(',');
        if (comma > 0) {
            return searchPlaces(q.substring(0, comma).trim(), q.substring(comma + 1).trim(), limit);
        }
        List<PlaceResult> all = searchPlaces(q, null, limit);
        int space = q.lastIndexOf(' ');
        if (all.isEmpty() && space > 0) {
            return searchPlaces(q.substring(0, space).trim(), q.substring(space + 1).trim(), limit);
        }
        return all;
    }

    private List<PlaceResult> searchPlaces(String place, String districtText, int limit) {
        String key = GeoNames.normalize(place);
        String districtKey = districtText == null ? "" : GeoNames.normalize(districtText);
        if (key.length() < 2 || (districtText != null && districtKey.length() < 2)) {
            return List.of();
        }
        String clean = place.replace("%", "").replace("_", "");
        String filter = (anyDistrictServed() ? " AND d.is_served = TRUE" : "")
                + (districtText == null ? "" : " AND REPLACE(REPLACE(REPLACE(LOWER(d.name), ' ', ''), '-', ''), '.', '') LIKE :dkey");
        MapSqlParameterSource p = new MapSqlParameterSource("like", "%" + clean + "%")
                .addValue("prefix", clean + "%").addValue("key", key + "%").addValue("exact", key)
                .addValue("dkey", districtKey + "%").addValue("n", limit);
        List<Named> wards = new ArrayList<>();
        jdbc.query("SELECT w.name, lb.name AS body, d.name AS district, w.centre_latitude, w.centre_longitude "
                        + "FROM wards w JOIN local_bodies lb ON lb.local_body_code = w.local_body_code "
                        + "JOIN geo_districts d ON d.district_code = lb.district_code "
                        + "WHERE w.is_active = TRUE AND w.centre_latitude IS NOT NULL AND w.name LIKE :like" + filter
                        + " ORDER BY w.name LIMIT :n", p,
                rs -> {
                    wards.add(new Named(rs.getString("name"), new PlaceResult(rs.getString("name"),
                            rs.getString("body") + ", " + rs.getString("district"),
                            rs.getDouble("centre_latitude"), rs.getDouble("centre_longitude"), "WARD", "DATASET")));
                });
        List<Named> offices = new ArrayList<>();
        jdbc.query("SELECT o.office_name, o.pincode, d.name AS district, o.latitude, o.longitude "
                        + "FROM pincode_offices o JOIN geo_districts d ON d.district_code = o.district_code "
                        + "WHERE o.latitude IS NOT NULL AND o.office_name LIKE :prefix" + filter
                        + " ORDER BY o.office_name LIMIT :n", p,
                rs -> {
                    String name = AdminAreaService.stripOfficeSuffix(rs.getString("office_name"));
                    offices.add(new Named(name, new PlaceResult(name + " (post office)",
                            rs.getString("pincode") + ", " + rs.getString("district"),
                            rs.getDouble("latitude"), rs.getDouble("longitude"), "POST_OFFICE", "DATASET")));
                });
        List<Named> villages = new ArrayList<>();
        jdbc.query("SELECT v.name, s.name AS sub, b.name AS block, d.name AS district, v.centre_latitude, v.centre_longitude "
                        + "FROM geo_villages v JOIN geo_districts d ON d.district_code = v.district_code "
                        + "JOIN geo_subdistricts s ON s.subdistrict_code = v.subdistrict_code "
                        + "LEFT JOIN geo_blocks b ON b.block_code = v.block_code "
                        + "WHERE v.centre_latitude IS NOT NULL AND v.name_key LIKE :key" + filter
                        + " ORDER BY v.name_key = :exact DESC, v.name LIMIT :n", p,
                rs -> {
                    String block = rs.getString("block");
                    villages.add(new Named(rs.getString("name"), new PlaceResult(rs.getString("name"),
                            "village, " + (block != null ? block + " block" : rs.getString("sub")) + ", " + rs.getString("district"),
                            rs.getDouble("centre_latitude"), rs.getDouble("centre_longitude"), "VILLAGE", "DATASET")));
                });
        return merge(key, villages, wards, offices, limit);
    }

    /** A result with the plain name it matched on (labels add " (post office)"). */
    record Named(String name, PlaceResult result) {
    }

    /**
     * Exact name matches first (villages, then wards, then post offices), then
     * the three lists take turns, keeping each list's own order; at most
     * {@code limit} results.
     */
    static List<PlaceResult> merge(String key, List<Named> villages, List<Named> wards, List<Named> offices, int limit) {
        List<List<Named>> lists = List.of(villages, wards, offices);
        List<PlaceResult> out = new ArrayList<>();
        java.util.Set<Named> used = new java.util.HashSet<>();
        for (List<Named> list : lists) {
            for (Named n : list) {
                if (out.size() < limit && GeoNames.normalize(n.name()).equals(key) && used.add(n)) {
                    out.add(n.result());
                }
            }
        }
        int[] next = new int[lists.size()];
        boolean added = true;
        while (out.size() < limit && added) {
            added = false;
            for (int i = 0; i < lists.size() && out.size() < limit; i++) {
                List<Named> list = lists.get(i);
                while (next[i] < list.size() && used.contains(list.get(next[i]))) {
                    next[i]++;
                }
                if (next[i] < list.size()) {
                    Named n = list.get(next[i]++);
                    used.add(n);
                    out.add(n.result());
                    added = true;
                }
            }
        }
        return out;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GeoRepository.class);
}
