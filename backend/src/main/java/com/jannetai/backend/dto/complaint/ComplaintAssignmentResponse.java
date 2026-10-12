package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.enums.ComplaintStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/v1/complaints/{id}/assignment - who the complaint is with and how
 * it got there (V36 complaint_assignments).
 *
 * <p>For a citizen, officer names and staff reasons are left out
 * ({@code officerName} / {@code entries[].officerName} / {@code reason} null):
 * the app says "an officer of {department} is handling it" only when an
 * officer is really assigned in this system ({@code officerAssigned}) - it
 * never names or implies a government official on its own (spec 2026-10-11).
 *
 * @param locationVerificationStatus VERIFIED_BOUNDARY / REQUIRES_VERIFICATION / STAFF_VERIFIED
 */
public record ComplaintAssignmentResponse(
        Long complaintId,
        String referenceNumber,
        ComplaintStatus status,
        Long departmentId,
        String departmentName,
        boolean officerAssigned,
        String officerName,
        String localBodyName,
        String wardName,
        String locationVerificationStatus,
        String verificationReason,
        List<Entry> history
) {
    /** One routing / reassignment step. {@code method}: RULE, AREA_RULE, FALLBACK, MANUAL. */
    public record Entry(LocalDateTime assignedAt, String departmentName, String officerName, String method,
                        String reason) {
    }
}
