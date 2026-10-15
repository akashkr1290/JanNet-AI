package com.jannetai.backend.dto.geo;

/**
 * V37: an LGD development block (prakhand, geo_blocks) - the routing level
 * between a Gram Panchayat and its district (DepartmentAssignmentService#forArea).
 */
public record BlockRef(Integer code, String name, Integer districtCode) {
}
