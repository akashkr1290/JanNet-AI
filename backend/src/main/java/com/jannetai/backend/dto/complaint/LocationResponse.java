package com.jannetai.backend.dto.complaint;

import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.enums.LocationSource;
import com.jannetai.backend.service.complaint.LocationFlags;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The complaint's INCIDENT location (where the problem is). V33 added the
 * location-check fields shown to staff: detected point, accuracy, photo time,
 * citizen confirmation, review flags and the optional submission distance
 * (never the citizen's own coordinates). All new fields are null/false/empty
 * for complaints created before V33.
 *
 * V34 adds the real area for staff and the citizen: PIN, landmark,
 * locality, district, Gram Panchayat / urban body, village and how the ward
 * was found ({@code wardMatch}). Null for complaints created before V34.
 *
 * V36 adds whether that area is proven: {@code locationVerificationStatus}
 * (VERIFIED_BOUNDARY / REQUIRES_VERIFICATION / STAFF_VERIFIED), the
 * {@code verificationReason} and a {@code suggestedWard*} staff may confirm.
 *
 * V37 adds the development block ({@code blockCode}, {@code blockName}).
 */
public record LocationResponse(
        Long locationId,
        BigDecimal latitude,
        BigDecimal longitude,
        Long wardId,
        String wardName,
        String formattedAddress,
        LocationSource source,
        Boolean outOfJurisdiction,
        BigDecimal accuracyMeters,
        LocalDateTime capturedAt,
        BigDecimal detectedLatitude,
        BigDecimal detectedLongitude,
        Boolean confirmedByCitizen,
        List<String> flags,
        BigDecimal submissionDistanceMeters,
        String pincode,
        String landmark,
        String locality,
        Integer districtCode,
        String districtName,
        Integer localBodyCode,
        String localBodyName,
        String localBodyType,
        Integer villageCode,
        String villageName,
        String wardNumber,
        String wardMatch,
        String locationVerificationStatus,
        String verificationReason,
        Long suggestedWardId,
        String suggestedWardName,
        Integer blockCode,
        String blockName
) {
    public static LocationResponse from(Location location) {
        return new LocationResponse(
                location.getLocationId(),
                location.getLatitude(),
                location.getLongitude(),
                location.getWard() != null ? location.getWard().getWardId() : null,
                location.getWard() != null ? location.getWard().getName() : null,
                location.getFormattedAddress(),
                location.getSource(),
                location.getOutOfJurisdiction(),
                location.getAccuracyMeters(),
                location.getCapturedAt(),
                location.getDetectedLatitude(),
                location.getDetectedLongitude(),
                Boolean.TRUE.equals(location.getConfirmedByCitizen()),
                LocationFlags.split(location.getFlags()),
                location.getSubmissionDistanceMeters(),
                location.getPincode(),
                location.getLandmark(),
                location.getLocality(),
                location.getDistrictCode(),
                location.getDistrict() != null ? location.getDistrict().getName() : null,
                location.getLocalBodyCode(),
                location.getLocalBody() != null ? location.getLocalBody().getName() : null,
                location.getLocalBody() != null ? location.getLocalBody().getBodyType() : null,
                location.getVillageCode(),
                location.getVillage() != null ? location.getVillage().getName() : null,
                location.getWard() != null ? location.getWard().getWardNumber() : null,
                location.getWardMatch(),
                location.getLocationVerificationStatus(),
                location.getVerificationReason(),
                location.getSuggestedWard() != null ? location.getSuggestedWard().getWardId() : null,
                location.getSuggestedWard() != null ? location.getSuggestedWard().getName() : null,
                location.getBlockCode(),
                location.getBlock() != null ? location.getBlock().getName() : null
        );
    }
}
