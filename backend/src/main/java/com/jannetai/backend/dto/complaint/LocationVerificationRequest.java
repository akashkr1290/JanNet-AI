package com.jannetai.backend.dto.complaint;

/**
 * PATCH /api/v1/complaints/{id}/location-verification - staff confirm the
 * location: {@code wardId} = the correct ward (may be the suggested one), or
 * null to confirm the area as it is (e.g. the body has no ward list).
 */
public record LocationVerificationRequest(Long wardId, String note) {
}
