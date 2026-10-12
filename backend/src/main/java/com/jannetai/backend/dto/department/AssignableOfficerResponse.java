package com.jannetai.backend.dto.department;

import com.jannetai.backend.entity.User;

/**
 * One entry of GET /departments/{id}/officers - the Department Head's officer
 * picker (pilot workflow 2026-09-30: the Department Head assigns every
 * complaint). Carries what automatic load balancing used to decide on, so the
 * Head can choose: the officer's self-reported availability (personal setting
 * officer_availability_status: AVAILABLE / BUSY / ON_LEAVE) and how many
 * complaints they currently have open (ASSIGNED or IN_PROGRESS).
 */
public record AssignableOfficerResponse(
        Long userId,
        String fullName,
        String email,
        String mobileNumber,
        String availability,
        long openComplaints,
        String area
) {
    public static AssignableOfficerResponse from(User officer, String availability, long openComplaints) {
        return new AssignableOfficerResponse(officer.getUserId(), officer.getFullName(), officer.getEmail(),
                officer.getMobileNumber(), availability, openComplaints, areaOf(officer));
    }

    /**
     * The area the officer works for (set by Admin): "Nagar Nigam / Gram
     * Panchayat · ward". Shown in the Department Head's picker so the right
     * officer is chosen for a complaint's ward. Null = not limited to an area.
     */
    static String areaOf(User officer) {
        String body = officer.getLocalBody() != null ? officer.getLocalBody().getName() : null;
        String ward = officer.getWard() != null ? officer.getWard().getName() : null;
        if (body != null && !body.isBlank() && ward != null && !ward.isBlank()) {
            return body + " · " + ward;
        }
        if (ward != null && !ward.isBlank()) {
            return ward;
        }
        return body != null && !body.isBlank() ? body : null;
    }
}
