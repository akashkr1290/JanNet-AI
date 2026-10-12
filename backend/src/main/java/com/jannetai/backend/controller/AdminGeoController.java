package com.jannetai.backend.controller;

import com.jannetai.backend.dto.geo.DistrictRef;
import com.jannetai.backend.dto.geo.VillageRef;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import com.jannetai.backend.service.geo.AdminAreaService;
import com.jannetai.backend.service.geo.GoogleQuota;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * V34 Admin settings for the location data (Flutter admin "Areas" screen):
 * which districts the deployment serves, a village's map centre, and
 * today's Google usage against the daily caps. The LGD lists themselves are
 * loaded with database/tools/lgd_import, not edited here.
 */
@RestController
@RequestMapping("/api/v1/admin/geo")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin - Areas", description = "Served districts, village centres, Google usage")
public class AdminGeoController {

    private final AdminAreaService areas;
    private final AuditService auditService;
    private final GoogleQuota googleQuota;

    public record ServedRequest(@NotNull Boolean served) {
    }

    public record CentreRequest(@NotNull @DecimalMin("6.0") @DecimalMax("38.0") BigDecimal latitude,
                                @NotNull @DecimalMin("68.0") @DecimalMax("98.0") BigDecimal longitude) {
    }

    /** Complaints from a district that is not served are flagged OUT_OF_JURISDICTION for review. */
    @PatchMapping("/districts/{code}/served")
    public DistrictRef setServed(@AuthenticationPrincipal UserPrincipal principal, @PathVariable int code,
                                 @Valid @RequestBody ServedRequest request) {
        DistrictRef d = areas.setDistrictServed(code, request.served());
        auditService.record(principal.getUser(), "DISTRICT_SERVED_CHANGED", "DISTRICT", (long) code,
                AuditJson.of("name", d.name(), "served", request.served()));
        return d;
    }

    /** Where the map opens for a village in the manual flow (Admin taps the map). */
    @PutMapping("/villages/{code}/centre")
    public VillageRef setVillageCentre(@AuthenticationPrincipal UserPrincipal principal, @PathVariable int code,
                                       @Valid @RequestBody CentreRequest request) {
        VillageRef v = areas.setVillageCentre(code, request.latitude(), request.longitude());
        auditService.record(principal.getUser(), "VILLAGE_CENTRE_SET", "VILLAGE", (long) code,
                AuditJson.of("name", v.name(), "latitude", request.latitude(), "longitude", request.longitude()));
        return v;
    }

    /** Google calls made today per SKU and the daily cap - shown so Admin can see the free amount is safe. */
    @GetMapping("/google-usage")
    public Map<String, Object> googleUsage() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        googleQuota.usedToday().forEach((sku, n) -> out.put(sku.name(), n));
        for (GoogleQuota.Sku sku : GoogleQuota.Sku.values()) {
            out.putIfAbsent(sku.name(), 0);
            out.put(sku.name() + "_LIMIT", googleQuota.limit(sku));
        }
        return out;
    }
}
