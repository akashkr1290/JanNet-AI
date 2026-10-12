package com.jannetai.backend.controller;

import com.jannetai.backend.dto.complaint.ComplaintAssignmentResponse;
import com.jannetai.backend.dto.complaint.LocationVerificationItem;
import com.jannetai.backend.dto.complaint.LocationVerificationRequest;
import com.jannetai.backend.security.UserPrincipal;
import com.jannetai.backend.service.complaint.LocationVerificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V36 (spec 2026-10-11): assignment status and the location verification
 * queue. Logic in service/complaint/LocationVerificationService.
 *
 * <p>Called from Flutter: the complaint detail screen
 * ({@code /{id}/assignment}) and the staff "Location check" queue
 * ({@code /location-verification}, {@code PATCH /{id}/location-verification}).
 */
@RestController
@RequestMapping("/api/v1/complaints")
@RequiredArgsConstructor
@Tag(name = "Complaint location & assignment", description = "Assignment history and manual location verification")
public class ComplaintLocationController {

    private final LocationVerificationService service;

    @GetMapping("/{id}/assignment")
    @PreAuthorize("isAuthenticated()")
    public ComplaintAssignmentResponse assignment(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return service.assignment(principal.getUser(), id);
    }

    @GetMapping("/location-verification")
    @PreAuthorize("hasAnyRole('GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'VERIFICATION_TEAM', 'ADMIN', 'SUPER_ADMIN')")
    public Page<LocationVerificationItem> queue(@AuthenticationPrincipal UserPrincipal principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return service.queue(principal.getUser(), page, pageSize);
    }

    @PatchMapping("/{id}/location-verification")
    @PreAuthorize("hasAnyRole('GOVERNMENT_OFFICER', 'DEPARTMENT_HEAD', 'VERIFICATION_TEAM', 'ADMIN', 'SUPER_ADMIN')")
    public ComplaintAssignmentResponse verify(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                              @RequestBody LocationVerificationRequest request) {
        return service.verify(principal.getUser(), id, request);
    }
}
