package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.ComplaintAssignmentResponse;
import com.jannetai.backend.dto.complaint.LocationVerificationItem;
import com.jannetai.backend.dto.complaint.LocationVerificationRequest;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.ComplaintAssignment;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.Ward;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LocationVerificationService} (V36): who sees what in the assignment
 * view, who sees which part of the location-check queue, and that confirming
 * a ward goes through LocationService and is audited.
 */
@ExtendWith(MockitoExtension.class)
class LocationVerificationServiceTest {

    @Mock private ComplaintService complaintService;
    @Mock private ComplaintRepository complaintRepository;
    @Mock private AssignmentHistoryService assignmentHistory;
    @Mock private LocationService locationService;
    @Mock private AuditService auditService;

    private LocationVerificationService service;

    private final User citizen = User.builder().userId(1L).role(Role.CITIZEN).build();
    private final Department roads = Department.builder().departmentId(5L).name("Roads").build();
    private final User officer = User.builder().userId(9L).fullName("A. Officer").role(Role.GOVERNMENT_OFFICER).build();

    @BeforeEach
    void setUp() {
        service = new LocationVerificationService(complaintService, complaintRepository, assignmentHistory,
                locationService, auditService);
    }

    private Complaint complaint(Location location) {
        return Complaint.builder().complaintId(41L).referenceNumber("JN-2026-000041").category(ComplaintCategory.POTHOLE)
                .status(ComplaintStatus.VERIFIED).department(roads).assignedOfficer(officer).location(location).build();
    }

    private Location needsCheck() {
        return Location.builder().locationId(7L).latitude(new BigDecimal("28.640000")).longitude(new BigDecimal("77.370000"))
                .locationVerificationStatus("REQUIRES_VERIFICATION").verificationReason("NO_WARD_BOUNDARY")
                .ward(Ward.builder().wardId(3L).name("Ward 3").build()).build();
    }

    private ComplaintAssignment step(String method, String reason) {
        return ComplaintAssignment.builder().department(roads).officer(officer).method(method).reason(reason).build();
    }

    @Test
    void aCitizenSeesTheDepartmentButNoOfficerNameAndNoStaffReason() {
        when(complaintRepository.findById(41L)).thenReturn(Optional.of(complaint(needsCheck())));
        when(assignmentHistory.history(41L)).thenReturn(List.of(step("RULE", "Category POTHOLE")));

        ComplaintAssignmentResponse r = service.assignment(citizen, 41L);

        verify(complaintService).getDetail(citizen, 41L); // same visibility rule as the complaint itself
        assertThat(r.departmentName()).isEqualTo("Roads");
        assertThat(r.officerAssigned()).isTrue();
        assertThat(r.officerName()).isNull();
        assertThat(r.locationVerificationStatus()).isEqualTo("REQUIRES_VERIFICATION");
        assertThat(r.history()).hasSize(1);
        assertThat(r.history().get(0).officerName()).isNull();
        assertThat(r.history().get(0).reason()).isNull();
        assertThat(r.history().get(0).method()).isEqualTo("RULE");
    }

    @Test
    void staffSeeTheOfficerAndTheReason() {
        User head = User.builder().userId(2L).role(Role.DEPARTMENT_HEAD).build();
        when(complaintRepository.findById(41L)).thenReturn(Optional.of(complaint(needsCheck())));
        when(assignmentHistory.history(41L)).thenReturn(List.of(step("MANUAL", "Moved by the head")));

        ComplaintAssignmentResponse r = service.assignment(head, 41L);

        assertThat(r.officerName()).isEqualTo("A. Officer");
        assertThat(r.history().get(0).officerName()).isEqualTo("A. Officer");
        assertThat(r.history().get(0).reason()).isEqualTo("Moved by the head");
    }

    @Test
    void noOfficerIsClaimedWhenNoneIsAssignedInThisSystem() {
        Complaint c = complaint(needsCheck());
        c.setAssignedOfficer(null);
        when(complaintRepository.findById(41L)).thenReturn(Optional.of(c));
        when(assignmentHistory.history(41L)).thenReturn(List.of());

        ComplaintAssignmentResponse r = service.assignment(citizen, 41L);

        assertThat(r.officerAssigned()).isFalse();
        assertThat(r.officerName()).isNull();
    }

    @Test
    void aDepartmentHeadWithoutADepartmentSeesAnEmptyQueue() {
        User head = User.builder().userId(2L).role(Role.DEPARTMENT_HEAD).build();

        Page<LocationVerificationItem> page = service.queue(head, 0, 20);

        assertThat(page.getContent()).isEmpty();
        verify(complaintRepository, never()).findLocationVerificationQueue(any(), any(), any(), any());
    }

    @Test
    void aDepartmentHeadSeesOnlyTheirDepartment() {
        User head = User.builder().userId(2L).role(Role.DEPARTMENT_HEAD).department(roads).build();
        when(complaintRepository.findLocationVerificationQueue(any(), eq(5L), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(complaint(needsCheck()))));

        Page<LocationVerificationItem> page = service.queue(head, 0, 20);

        assertThat(page.getContent()).hasSize(1);
        LocationVerificationItem item = page.getContent().get(0);
        assertThat(item.reason()).isEqualTo("NO_WARD_BOUNDARY");
        assertThat(item.wardName()).isEqualTo("Ward 3");
        assertThat(item.referenceNumber()).isEqualTo("JN-2026-000041");
    }

    @Test
    void anOfficerSeesOnlyTheirOwnComplaintsAndTheVerificationTeamSeesAll() {
        when(complaintRepository.findLocationVerificationQueue(any(), isNull(), eq(9L), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(complaintRepository.findLocationVerificationQueue(any(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(complaint(needsCheck()))));

        assertThat(service.queue(officer, 0, 20).getContent()).isEmpty();
        User team = User.builder().userId(3L).role(Role.VERIFICATION_TEAM).build();
        assertThat(service.queue(team, 0, 20).getContent()).hasSize(1);
    }

    @Test
    void confirmingAWardGoesThroughLocationServiceAndIsAudited() {
        User head = User.builder().userId(2L).role(Role.DEPARTMENT_HEAD).department(roads).build();
        Location location = needsCheck();
        when(complaintRepository.findById(41L)).thenReturn(Optional.of(complaint(location)));
        when(assignmentHistory.history(41L)).thenReturn(List.of());

        service.verify(head, 41L, new LocationVerificationRequest(12L, "Checked on the ward map"));

        verify(locationService).verifyByStaff(location, 12L);
        verify(auditService).record(eq(head), eq("COMPLAINT_LOCATION_VERIFIED"), eq("COMPLAINT"), eq(41L), any());
    }

    @Test
    void aComplaintWithoutALocationCannotBeVerified() {
        User head = User.builder().userId(2L).role(Role.DEPARTMENT_HEAD).department(roads).build();
        when(complaintRepository.findById(41L)).thenReturn(Optional.of(complaint(null)));

        assertThatThrownBy(() -> service.verify(head, 41L, new LocationVerificationRequest(12L, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(locationService, never()).verifyByStaff(any(), any());
    }
}
