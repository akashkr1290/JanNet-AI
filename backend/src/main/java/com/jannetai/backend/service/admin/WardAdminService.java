package com.jannetai.backend.service.admin;

import com.jannetai.backend.dto.ward.AdminWardResponse;
import com.jannetai.backend.dto.ward.WardLocalityRequest;
import com.jannetai.backend.dto.ward.WardUpsertRequest;
import com.jannetai.backend.service.geo.GeoRepository;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.WardRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.geo.WardBoundaryValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * Audit GAP-020 (SRS 15.11 "ward/zone configuration", 13.4, 16.3 ward-boundary
 * editor): Admin create/update/activate/deactivate of wards.
 *
 * <ul>
 *   <li>Boundaries are validated by {@link WardBoundaryValidator} and must not
 *       overlap any other ACTIVE ward's boundary (409). Neighbouring wards may
 *       share a border.</li>
 *   <li>The stored boundary is what LocationService's reverse geocoding
 *       (WardLocator, audit GAP-008) reads on every submission - there is no
 *       cache, so a saved change applies to the next complaint.</li>
 *   <li>Rows are never deleted (users, locations and reports reference them);
 *       a deactivated ward is no longer offered or matched.</li>
 *   <li>Every change is written to the audit log with the previous values,
 *       including the full previous boundary, so each version can be
 *       recovered (SRS 15.11 "all configuration changes versioned and logged").</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WardAdminService {

    private final WardRepository wardRepository;
    private final AuditService auditService;
    private final GeoRepository geoRepository; // V34: the ward's body, its listed localities

    @Transactional(readOnly = true)
    public List<AdminWardResponse> listAll() {
        // V36: wards of local bodies (LGD / SBM - tens of thousands) are listed per
        // body (?localBodyCode=); without a body only the legacy Admin wards.
        return wardRepository.findByLocalBodyCodeIsNullOrderByNameAsc().stream().map(AdminWardResponse::from).toList();
    }

    /** V34: wards of one body (Admin picks the body with the location picker first). */
    @Transactional(readOnly = true)
    public List<AdminWardResponse> listForBody(int localBodyCode) {
        return wardRepository.findByLocalBodyCodeOrderByNameAsc(localBodyCode).stream().map(AdminWardResponse::from).toList();
    }

    @Transactional
    public AdminWardResponse create(User admin, WardUpsertRequest request) {
        String name = request.name().trim();
        String code = blankToNull(request.code());
        Integer body = requireBody(request.localBodyCode());
        requireCentre(request);
        String boundary = validatedBoundary(request.boundaryGeojson(), null, body);
        requireUniqueName(name, null, body);
        requireUniqueCode(code, null);

        Ward ward = wardRepository.save(Ward.builder()
                .name(name)
                .code(code)
                .localBodyCode(body)
                .wardNumber(blankToNull(request.wardNumber()))
                .centreLatitude(request.centreLatitude())
                .centreLongitude(request.centreLongitude())
                .boundaryGeojson(boundary)
                .isActive(true)
                .build());
        auditService.record(admin, "WARD_CREATED", "WARD", ward.getWardId(),
                AuditJson.of("name", name, "code", code, "local_body_code", body, "ward_number", ward.getWardNumber(),
                        "boundary_geojson", boundary));
        return AdminWardResponse.from(ward);
    }

    @Transactional
    public AdminWardResponse update(User admin, Long wardId, WardUpsertRequest request) {
        Ward ward = requireWard(wardId);
        String name = request.name().trim();
        String code = blankToNull(request.code());
        // V34: a ward never moves to another body (complaints and officers refer to it);
        // a legacy ward may be attached to a body once.
        Integer body = request.localBodyCode() == null ? ward.getLocalBodyCode() : requireBody(request.localBodyCode());
        if (ward.getLocalBodyCode() != null && !ward.getLocalBodyCode().equals(body)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A ward cannot be moved to another local body");
        }
        requireCentre(request);
        String boundary = validatedBoundary(request.boundaryGeojson(), Boolean.TRUE.equals(ward.getIsActive()) ? wardId : null, body);
        requireUniqueName(name, wardId, body);
        requireUniqueCode(code, wardId);

        String previousName = ward.getName();
        String previousCode = ward.getCode();
        String previousBoundary = ward.getBoundaryGeojson();
        ward.setName(name);
        ward.setCode(code);
        ward.setLocalBodyCode(body);
        ward.setWardNumber(blankToNull(request.wardNumber()));
        ward.setCentreLatitude(request.centreLatitude());
        ward.setCentreLongitude(request.centreLongitude());
        ward.setBoundaryGeojson(boundary);
        ward = wardRepository.save(ward);
        auditService.record(admin, "WARD_UPDATED", "WARD", wardId, AuditJson.of(
                "name", name, "previous_name", previousName,
                "code", code, "previous_code", previousCode,
                "boundary_changed", !Objects.equals(previousBoundary, boundary),
                "boundary_geojson", boundary, "previous_boundary_geojson", previousBoundary));
        return AdminWardResponse.from(ward);
    }

    @Transactional
    public AdminWardResponse setActive(User admin, Long wardId, boolean active) {
        Ward ward = requireWard(wardId);
        if (Boolean.valueOf(active).equals(ward.getIsActive())) {
            return AdminWardResponse.from(ward);
        }
        if (active && ward.getBoundaryGeojson() != null) {
            // re-activation must not create an overlap with wards activated meanwhile
            requireNoOverlap(WardBoundaryValidator.parseStoredOrEmpty(ward.getBoundaryGeojson()), wardId, ward.getLocalBodyCode());
        }
        ward.setIsActive(active);
        ward = wardRepository.save(ward);
        auditService.record(admin, active ? "WARD_ACTIVATED" : "WARD_DEACTIVATED", "WARD", wardId,
                AuditJson.of("name", ward.getName()));
        return AdminWardResponse.from(ward);
    }

    /** Normalised GeoJSON, or null for "no boundary"; overlap-checked against the other active wards. */
    private String validatedBoundary(String geojson, Long selfId, Integer body) {
        if (geojson == null || geojson.isBlank()) {
            return null;
        }
        WardBoundaryValidator.Result result = WardBoundaryValidator.validate(geojson); // 400 on invalid geometry
        requireNoOverlap(result.polygons(), selfId, body);
        return result.normalizedGeojson();
    }

    /** V34: only wards of the same body can overlap; different bodies are checked by their own boundaries. */
    private void requireNoOverlap(List<List<List<double[]>>> polygons, Long selfId, Integer body) {
        List<Ward> sameScope = body == null ? wardRepository.findByLocalBodyCodeIsNullAndIsActiveTrueOrderByNameAsc()
                : wardRepository.findByLocalBodyCodeOrderByNameAsc(body).stream().filter(w -> Boolean.TRUE.equals(w.getIsActive())).toList();
        for (Ward other : sameScope) {
            if (other.getWardId().equals(selfId) || other.getBoundaryGeojson() == null
                    || !Objects.equals(other.getLocalBodyCode(), body)) {
                continue;
            }
            if (WardBoundaryValidator.overlaps(polygons, WardBoundaryValidator.parseStoredOrEmpty(other.getBoundaryGeojson()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Boundary overlaps the boundary of ward '" + other.getName() + "' - wards may share a border but not area");
            }
        }
    }

    /** Names are unique per body (V34 uq_wards_body_name); legacy wards among legacy wards. */
    private void requireUniqueName(String name, Long selfId, Integer body) {
        java.util.Optional<Ward> same = body == null
                ? wardRepository.findFirstByName(name).filter(w -> w.getLocalBodyCode() == null)
                : wardRepository.findFirstByLocalBodyCodeAndName(body, name);
        same.filter(w -> !w.getWardId().equals(selfId)).ifPresent(w -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A ward named '" + w.getName() + "' already exists");
        });
    }

    private Integer requireBody(Integer localBodyCode) {
        if (localBodyCode == null) {
            return null;
        }
        geoRepository.localBody(localBodyCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown local body " + localBodyCode));
        return localBodyCode;
    }

    private static void requireCentre(WardUpsertRequest request) {
        if ((request.centreLatitude() == null) != (request.centreLongitude() == null)) {
            throw new IllegalArgumentException("centreLatitude and centreLongitude must be given together");
        }
    }

    // ---------------------------------------------------------------- V34 localities

    /** Adds a colony / mohalla name to a ward (AreaResolver then finds the ward from Google's colony name). */
    @Transactional
    public List<String> addLocality(User admin, Long wardId, WardLocalityRequest request) {
        Ward ward = requireWard(wardId);
        if (ward.getLocalBodyCode() == null) {
            throw new IllegalArgumentException("Attach the ward to its local body first");
        }
        if (request.villageCode() != null) {
            geoRepository.village(request.villageCode())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown village " + request.villageCode()));
        }
        geoRepository.addWardLocality(wardId, request.name(), request.villageCode());
        auditService.record(admin, "WARD_LOCALITY_ADDED", "WARD", wardId,
                AuditJson.of("locality", request.name().trim(), "village_code", request.villageCode()));
        return localitiesOf(ward);
    }

    @Transactional
    public List<String> removeLocality(User admin, Long wardId, String name) {
        Ward ward = requireWard(wardId);
        geoRepository.removeWardLocality(wardId, name);
        auditService.record(admin, "WARD_LOCALITY_REMOVED", "WARD", wardId, AuditJson.of("locality", name));
        return localitiesOf(ward);
    }

    private List<String> localitiesOf(Ward ward) {
        return geoRepository.wardsOfBody(ward.getLocalBodyCode()).stream()
                .filter(w -> w.wardId().equals(ward.getWardId()))
                .findFirst().map(com.jannetai.backend.dto.geo.WardRef::localities).orElse(List.of());
    }

    private void requireUniqueCode(String code, Long selfId) {
        if (code == null) {
            return;
        }
        wardRepository.findFirstByCode(code).filter(w -> !w.getWardId().equals(selfId)).ifPresent(w -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ward code '" + code + "' is already used by '" + w.getName() + "'");
        });
    }

    private Ward requireWard(Long wardId) {
        return wardRepository.findById(wardId).orElseThrow(() -> new ResourceNotFoundException("Ward not found: " + wardId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
