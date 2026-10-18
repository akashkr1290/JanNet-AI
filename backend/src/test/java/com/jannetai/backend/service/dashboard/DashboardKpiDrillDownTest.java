package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.complaint.ComplaintSummaryResponse;
import com.jannetai.backend.dto.dashboard.DashboardKpi;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dashboard drill-down: a tap on a KPI tile lists the complaints behind it,
 * with the tiles' own conditions and the same role scoping.
 */
@ExtendWith(MockitoExtension.class)
class DashboardKpiDrillDownTest {

    @Mock private AnalyticsCacheService analyticsCacheService;
    @Mock private ComplaintRepository complaintRepository;
    private GovernmentDashboardService service;

    private final User admin = User.builder().userId(1L).role(Role.ADMIN).fullName("Admin").build();
    private final User head = User.builder().userId(2L).role(Role.DEPARTMENT_HEAD).fullName("Head")
            .department(Department.builder().departmentId(7L).name("Public Works").isActive(true).build()).build();
    private final Page<Complaint> one = new PageImpl<>(List.of(Complaint.builder().complaintId(11L)
            .referenceNumber("JN-2026-000011").status(ComplaintStatus.ASSIGNED).isEscalated(false).isReopened(false).build()));

    @BeforeEach
    void setUp() {
        service = new GovernmentDashboardService(analyticsCacheService, complaintRepository);
    }

    @Test
    void openTileListsVerifiedAssignedAndInProgressNewestFirst() {
        when(complaintRepository.findByStatusIn(eq(AnalyticsAggregationService.OPEN_STATUSES), any(Pageable.class))).thenReturn(one);

        Page<ComplaintSummaryResponse> page = service.kpiComplaints(admin, null, DashboardKpi.OPEN, 0, 20);

        assertThat(page.getContent()).extracting(ComplaintSummaryResponse::referenceNumber).containsExactly("JN-2026-000011");
        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(complaintRepository).findByStatusIn(eq(AnalyticsAggregationService.OPEN_STATUSES), p.capture());
        assertThat(p.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(AnalyticsAggregationService.OPEN_STATUSES)
                .containsExactlyInAnyOrder(ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
    }

    @Test
    void eachTileUsesItsOwnCondition() {
        when(complaintRepository.findAll(any(Pageable.class))).thenReturn(one);
        when(complaintRepository.findByStatusIn(eq(AnalyticsAggregationService.RESOLVED_STATUSES), any(Pageable.class))).thenReturn(one);
        when(complaintRepository.findByIsEscalated(eq(true), any(Pageable.class))).thenReturn(one);

        service.kpiComplaints(admin, null, DashboardKpi.TOTAL, 0, 20);
        service.kpiComplaints(admin, null, DashboardKpi.RESOLVED, 0, 20);
        service.kpiComplaints(admin, null, DashboardKpi.ESCALATED, 0, 20);

        verify(complaintRepository).findAll(any(Pageable.class));
        verify(complaintRepository).findByStatusIn(eq(AnalyticsAggregationService.RESOLVED_STATUSES), any(Pageable.class));
        verify(complaintRepository).findByIsEscalated(eq(true), any(Pageable.class));
    }

    @Test
    void adminCanNarrowToOneDepartment() {
        when(complaintRepository.findByDepartment_DepartmentIdAndIsEscalated(eq(3L), eq(true), any(Pageable.class))).thenReturn(one);

        assertThat(service.kpiComplaints(admin, 3L, DashboardKpi.ESCALATED, 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test
    void departmentHeadAlwaysGetsOwnDepartment() {
        when(complaintRepository.findByDepartment_DepartmentId(eq(7L), any(Pageable.class))).thenReturn(one);

        service.kpiComplaints(head, null, DashboardKpi.TOTAL, 0, 20);

        verify(complaintRepository).findByDepartment_DepartmentId(eq(7L), any(Pageable.class));
    }

    @Test
    void departmentHeadCannotListAnotherDepartment() {
        assertThatThrownBy(() -> service.kpiComplaints(head, 3L, DashboardKpi.OPEN, 0, 20))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(complaintRepository);
    }

    @Test
    void pageSizeIsCapped() {
        when(complaintRepository.findAll(any(Pageable.class))).thenReturn(one);

        service.kpiComplaints(admin, null, DashboardKpi.TOTAL, -3, 5000);

        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(complaintRepository).findAll(p.capture());
        assertThat(p.getValue().getPageNumber()).isZero();
        assertThat(p.getValue().getPageSize()).isEqualTo(100);
    }
}
