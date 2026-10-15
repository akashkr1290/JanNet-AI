package com.jannetai.backend.dto.department;

import com.jannetai.backend.entity.Department;

public record DepartmentResponse(
        Long departmentId,
        String name,
        String description,
        Long headUserId,
        Boolean isActive,
        // V34 area served (null = every area)
        Integer districtCode,
        String districtName,
        Integer localBodyCode,
        String localBodyName,
        // V37 development block served (null = not a block department)
        Integer blockCode,
        String blockName
) {
    public static DepartmentResponse from(Department d) {
        return new DepartmentResponse(
                d.getDepartmentId(),
                d.getName(),
                d.getDescription(),
                d.getHeadUser() != null ? d.getHeadUser().getUserId() : null,
                d.getIsActive(),
                d.getDistrictCode(),
                d.getDistrict() != null ? d.getDistrict().getName() : null,
                d.getLocalBodyCode(),
                d.getLocalBody() != null ? d.getLocalBody().getName() : null,
                d.getBlockCode(),
                d.getBlock() != null ? d.getBlock().getName() : null
        );
    }
}
