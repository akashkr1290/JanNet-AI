package com.jannetai.backend.dto.admin;

/**
 * V34: PATCH /api/v1/admin/users/{id}/area - move a staff member to another
 * place (transfer), chosen with the Admin location picker. All null = no
 * area (works everywhere). Same checks as on create (AdminUserService#staffArea).
 */
public record AdminUpdateAreaRequest(Integer districtCode, Integer localBodyCode, Long wardId) {
}
