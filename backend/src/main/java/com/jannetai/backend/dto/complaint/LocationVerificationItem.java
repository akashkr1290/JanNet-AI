package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of GET /api/v1/complaints/location-verification - a complaint whose
 * ward / area could not be proven by a published boundary.
 *
 * @param reason e.g. NO_WARD_BOUNDARY, WARD_CHOSEN_BY_CITIZEN, WARD_DIFFERS_FROM_MAP,
 *               AREA_CHOSEN_BY_CITIZEN, AREA_DIFFERS_FROM_MAP, LOW_GPS_ACCURACY,
 *               NO_COORDINATES, AREA_FROM_ADDRESS_NAMES, AREA_UNKNOWN
 */
public record LocationVerificationItem(
        Long complaintId,
        String referenceNumber,
        ComplaintCategory category,
        ComplaintStatus status,
        String departmentName,
        Integer localBodyCode,
        String localBodyName,
        Long wardId,
        String wardName,
        Long suggestedWardId,
        String suggestedWardName,
        String reason,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDateTime createdAt
) {
}
