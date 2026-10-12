package com.jannetai.backend.dto.department;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Audit GAP-020 (SRS 15.11, 13.4): create or replace a department.
 * {@code headUserId} must be an ACTIVE DEPARTMENT_HEAD account that already
 * belongs to this department (assign the department on the user first via
 * Admin user management); null clears the head. Not accepted on create,
 * because no account can belong to a department that does not exist yet.
 *
 * V34: {@code districtCode} / {@code localBodyCode} - the area the department
 * serves, chosen in Admin with the location picker (state -> district ->
 * Gram Panchayat / urban body). Both null = every area. With a body, the
 * district is taken from the body. Complaints of that area are routed here
 * when the routing rule names a department of the same name
 * (DepartmentAssignmentService#forArea).
 */
public record DepartmentUpsertRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 300) String description,
        Long headUserId,
        Integer districtCode,
        Integer localBodyCode
) {
    /** Before V34 (no area) - existing callers and tests. */
    public DepartmentUpsertRequest(String name, String description, Long headUserId) {
        this(name, description, headUserId, null, null);
    }
}
