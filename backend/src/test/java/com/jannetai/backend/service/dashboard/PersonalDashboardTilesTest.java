package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.complaint.ComplaintSummaryResponse;
import com.jannetai.backend.dto.dashboard.OfficerDashboardResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.service.department.SlaPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every tile of the citizen and officer dashboards opens the complaints it counts. */
class PersonalDashboardTilesTest {

    private final ComplaintRepository complaints = mock(ComplaintRepository.class);
    private final StatusHistoryRepository history = mock(StatusHistoryRepository.class);
    private final SlaPolicy sla = mock(SlaPolicy.class);
    private final PersonalDashboardService service =
            new PersonalDashboardService(complaints, mock(PredictionRepository.class), history, sla);

    private final User citizen = User.builder().userId(42L).role(Role.CITIZEN).fullName("Asha Rao").build();
    private final User officer = User.builder().userId(7L).role(Role.GOVERNMENT_OFFICER).fullName("Officer").build();
    private final LocalDateTime now = LocalDateTime.now();

    private Complaint complaint(long id, ComplaintStatus status, LocalDateTime createdAt) {
        return Complaint.builder().complaintId(id).referenceNumber("JN-2026-%06d".formatted(id)).status(status)
                .createdAt(createdAt).isEscalated(false).isReopened(false).corroborationCount(1).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void citizenPendingTileUsesTheDashboardGroupNewestFirst() {
        Page<Complaint> one = new PageImpl<>(List.of(complaint(1, ComplaintStatus.VERIFIED, now)));
        when(complaints.findByCitizen_UserIdAndStatusIn(eq(42L), any(), any(Pageable.class))).thenReturn(one);

        Page<ComplaintSummaryResponse> page = service.tileComplaints(citizen, "pending", 0, 20);

        assertThat(page.getContent()).extracting(ComplaintSummaryResponse::referenceNumber).containsExactly("JN-2026-000001");
        ArgumentCaptor<Collection<ComplaintStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(complaints).findByCitizen_UserIdAndStatusIn(eq(42L), statuses.capture(), pageable.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING,
                ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void citizenTotalAndSingleStatusTiles() {
        when(complaints.findByCitizen_UserId(eq(42L), any(Pageable.class))).thenReturn(Page.empty());
        when(complaints.findByCitizen_UserIdAndStatusIn(eq(42L), any(), any(Pageable.class))).thenReturn(Page.empty());

        service.tileComplaints(citizen, "TOTAL", 0, 20);
        service.tileComplaints(citizen, "REJECTED", 0, 20);

        verify(complaints).findByCitizen_UserId(eq(42L), any(Pageable.class));
        verify(complaints).findByCitizen_UserIdAndStatusIn(eq(42L), eq(EnumSet.of(ComplaintStatus.REJECTED)), any(Pageable.class));
    }

    @Test
    void unknownTileIsABadRequest() {
        assertThatThrownBy(() -> service.tileComplaints(citizen, "OVERDUE", 0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.tileComplaints(officer, "PENDING", 0, 20))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void officerStatusTileIsScopedToTheOfficer() {
        when(complaints.findByAssignedOfficer_UserIdAndStatusIn(eq(7L), any(), any(Pageable.class))).thenReturn(Page.empty());

        service.tileComplaints(officer, "RESOLVED", 0, 20);

        verify(complaints).findByAssignedOfficer_UserIdAndStatusIn(eq(7L), eq(EnumSet.of(ComplaintStatus.RESOLVED)),
                any(Pageable.class));
    }

    @Test
    void slaTilesListExactlyWhatTheDashboardCounts() {
        Complaint overdue = complaint(11, ComplaintStatus.IN_PROGRESS, now.minusHours(30));
        Complaint atRisk = complaint(12, ComplaintStatus.ASSIGNED, now.minusHours(9));
        Complaint onTrack = complaint(13, ComplaintStatus.ASSIGNED, now.minusHours(1));
        Complaint noSla = complaint(14, ComplaintStatus.ASSIGNED, now.minusHours(2));
        when(sla.dueAt(overdue)).thenReturn(now.minusHours(6));
        when(sla.dueAt(atRisk)).thenReturn(now.plusHours(1));
        when(sla.dueAt(onTrack)).thenReturn(now.plusHours(47));
        when(sla.dueAt(noSla)).thenReturn(null);
        when(complaints.findByAssignedOfficer_UserIdAndStatusIn(eq(7L), any()))
                .thenReturn(List.of(overdue, atRisk, onTrack, noSla));
        when(complaints.countByStatusForOfficer(anyLong())).thenReturn(List.of());
        when(history.findByActor_UserIdAndNewStatusAndChangedAtGreaterThanEqual(anyLong(), any(), any()))
                .thenReturn(List.of());

        OfficerDashboardResponse d = service.officerDashboard(officer);

        assertThat(ids(service.tileComplaints(officer, "OVERDUE", 0, 20))).containsExactly(11L);
        assertThat(ids(service.tileComplaints(officer, "AT_RISK", 0, 20))).containsExactly(12L);
        assertThat(ids(service.tileComplaints(officer, "ON_TRACK", 0, 20))).containsExactly(13L);
        assertThat(ids(service.tileComplaints(officer, "NO_SLA", 0, 20))).containsExactly(14L);
        assertThat(List.of(d.overdue(), d.atRisk(), d.onTrack(), d.noSla())).containsExactly(1L, 1L, 1L, 1L);
    }

    @Test
    void slaTilesArePagedMostUrgentFirst() {
        Complaint a = complaint(21, ComplaintStatus.ASSIGNED, now.minusHours(50));
        Complaint b = complaint(22, ComplaintStatus.ASSIGNED, now.minusHours(50));
        when(sla.dueAt(a)).thenReturn(now.minusHours(2));
        when(sla.dueAt(b)).thenReturn(now.minusHours(20));
        when(complaints.findByAssignedOfficer_UserIdAndStatusIn(eq(7L), any())).thenReturn(List.of(a, b));

        Page<ComplaintSummaryResponse> first = service.tileComplaints(officer, "overdue", 0, 1);
        Page<ComplaintSummaryResponse> second = service.tileComplaints(officer, "overdue", 1, 1);

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(ids(first)).containsExactly(22L);
        assertThat(ids(second)).containsExactly(21L);
        assertThat(service.tileComplaints(officer, "overdue", 5, 1).getContent()).isEmpty();
    }

    private static List<Long> ids(Page<ComplaintSummaryResponse> page) {
        return page.getContent().stream().map(ComplaintSummaryResponse::complaintId).toList();
    }
}
