package com.jannetai.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * V36 complaint_assignments: one row per department / officer assignment of
 * a complaint (automatic routing or manual reassignment), so the full chain
 * stays visible after later changes. Written only by
 * service/complaint/AssignmentHistoryService; read by
 * GET /api/v1/complaints/{id}/assignment.
 */
@Entity
@Table(name = "complaint_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplaintAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long assignmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false)
    private Complaint complaint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "officer_id")
    private User officer;

    /** Area the routing used (the complaint's local body), NULL for a global department. */
    @Column(name = "local_body_code", columnDefinition = "INT UNSIGNED")
    private Integer localBodyCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id")
    private Ward ward;

    @Column(name = "routing_rule_id")
    private Long routingRuleId;

    /** RULE (category rule), AREA_RULE (rule -> the area's own department), FALLBACK, MANUAL. */
    @Column(name = "method", nullable = false, length = 20)
    private String method;

    @Column(name = "reason", length = 300)
    private String reason;

    /** Staff member for MANUAL; null = the system. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime assignedAt;
}
