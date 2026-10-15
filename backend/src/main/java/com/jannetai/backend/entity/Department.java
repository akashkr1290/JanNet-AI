package com.jannetai.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Government departments responsible for complaint categories.
 * Maps onto database/migrations/V2__create_departments.sql +
 * V4__add_departments_head_user_fk.sql.
 */
@Entity
@Table(name = "departments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    /**
     * V34 area served: a whole district, or one Gram Panchayat / urban body.
     * Both NULL = every area (the original departments). Routing
     * (DepartmentAssignmentService) picks the most specific department for
     * the complaint's area. Name is unique per area (uq_departments_name_scope).
     */
    @Column(name = "district_code", columnDefinition = "INT UNSIGNED")
    private Integer districtCode;

    @Column(name = "local_body_code", columnDefinition = "INT UNSIGNED")
    private Integer localBodyCode;

    /**
     * V37: the department serves one development block (districtCode is then
     * the block's district and localBodyCode is NULL - chk_departments_block_scope).
     */
    @Column(name = "block_code", columnDefinition = "INT UNSIGNED")
    private Integer blockCode;

    /** Read-only, for names in DepartmentResponse (the codes above are what is written). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "district_code", insertable = false, updatable = false)
    private GeoDistrict district;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "local_body_code", insertable = false, updatable = false)
    private LocalBody localBody;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_code", insertable = false, updatable = false)
    private GeoBlock block;

    /** FK added in V4, deliberately deferred at schema-creation time (V2 header comment). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "head_user_id")
    private User headUser;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
