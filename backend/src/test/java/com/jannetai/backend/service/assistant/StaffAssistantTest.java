package com.jannetai.backend.service.assistant;

import com.jannetai.backend.dto.assistant.AssistantAction;
import com.jannetai.backend.dto.assistant.AssistantCard;
import com.jannetai.backend.dto.assistant.AssistantMessageRequest;
import com.jannetai.backend.dto.assistant.AssistantReply;
import com.jannetai.backend.dto.dashboard.DepartmentComparisonResponse;
import com.jannetai.backend.dto.dashboard.OfficerDashboardResponse;
import com.jannetai.backend.dto.department.DepartmentPerformanceResponse;
import com.jannetai.backend.dto.department.OfficerWorkloadResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintCategory;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.service.dashboard.GovernmentDashboardService;
import com.jannetai.backend.service.dashboard.PersonalDashboardService;
import com.jannetai.backend.service.department.DepartmentPerformanceService;
import com.jannetai.backend.service.department.SlaPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JanNet Sahayak v2 (staff): every answer comes from the same data and rules
 * as the staff screens, and is scoped like them - an officer sees only their
 * own complaints, a department head only their own department, an admin all
 * departments. The department always comes from the account, never the message.
 */
class StaffAssistantTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);

    private final Department publicWorks = Department.builder().departmentId(3L).name("Public Works Gaya").build();
    private final Department waterWorks = Department.builder().departmentId(4L).name("Water Works Patna").build();

    private final User officer = User.builder().userId(7L).role(Role.GOVERNMENT_OFFICER).fullName("Ramesh Singh")
            .department(publicWorks).build();
    private final User otherOfficer = User.builder().userId(70L).role(Role.GOVERNMENT_OFFICER).fullName("Ravi Kumar")
            .department(publicWorks).build();
    private final User waterOfficer = User.builder().userId(80L).role(Role.GOVERNMENT_OFFICER).fullName("Sita Devi")
            .department(waterWorks).build();
    private final User head = User.builder().userId(8L).role(Role.DEPARTMENT_HEAD).fullName("Meena Sharma")
            .department(publicWorks).build();
    private final User headWithoutDepartment = User.builder().userId(9L).role(Role.DEPARTMENT_HEAD).fullName("Anil Rao").build();
    private final User admin = User.builder().userId(1L).role(Role.ADMIN).fullName("Kavita Jain").build();
    private final User citizen = User.builder().userId(42L).role(Role.CITIZEN).fullName("Asha Rao").build();

    // overdue 2 days (mine), at risk (mine), overdue 3 hours (colleague), overdue 9 days (other department),
    // waiting for an officer in each department, and an unrouted rejected complaint
    private final List<Complaint> db = List.of(
            complaint(201, ComplaintCategory.POTHOLE, ComplaintStatus.ASSIGNED, at(14, 12), at(17, 12), publicWorks, officer),
            complaint(202, ComplaintCategory.GARBAGE_OVERFLOW, ComplaintStatus.IN_PROGRESS, at(18, 12), at(19, 13), publicWorks, officer),
            complaint(203, ComplaintCategory.OPEN_MANHOLE, ComplaintStatus.ASSIGNED, at(18, 9), at(19, 9), publicWorks, otherOfficer),
            complaint(204, ComplaintCategory.WATER_LEAKAGE, ComplaintStatus.ASSIGNED, at(5, 9), at(10, 9), waterWorks, waterOfficer),
            complaint(205, ComplaintCategory.POTHOLE, ComplaintStatus.VERIFIED, at(12, 10), null, publicWorks, null),
            complaint(206, ComplaintCategory.WATER_LEAKAGE, ComplaintStatus.VERIFIED, at(15, 10), null, waterWorks, null),
            complaint(207, ComplaintCategory.GENERAL, ComplaintStatus.REJECTED, at(18, 10), null, null, null));

    private final ComplaintRepository complaints = mock(ComplaintRepository.class);
    private final SlaPolicy sla = mock(SlaPolicy.class);
    private final PersonalDashboardService personalDashboard = mock(PersonalDashboardService.class);
    private final DepartmentPerformanceService departmentPerformance = mock(DepartmentPerformanceService.class);
    private final GovernmentDashboardService governmentDashboard = mock(GovernmentDashboardService.class);
    private final StaffAssistant staff;
    private final AssistantService assistant;
    private final AssistantTestWorld w = new AssistantTestWorld();

    StaffAssistantTest() {
        when(sla.dueAt(any())).thenAnswer(inv -> ((Complaint) inv.getArgument(0)).getSlaDueAt());
        // the dashboard's own SLA rule, not a copy of it
        PersonalDashboardService rules = new PersonalDashboardService(mock(ComplaintRepository.class),
                mock(PredictionRepository.class), mock(StatusHistoryRepository.class), sla);
        when(personalDashboard.slaBucket(any(), any())).thenAnswer(inv -> rules.slaBucket(inv.getArgument(0), inv.getArgument(1)));

        when(complaints.findByAssignedOfficer_UserIdAndStatusIn(anyLong(), anyCollection())).thenAnswer(inv -> db.stream()
                .filter(c -> c.getAssignedOfficer() != null && c.getAssignedOfficer().getUserId().equals(inv.getArgument(0)))
                .filter(c -> ((Collection<?>) inv.getArgument(1)).contains(c.getStatus())).toList());
        when(complaints.findByDepartment_DepartmentIdAndStatusIn(anyLong(), anyCollection())).thenAnswer(inv -> db.stream()
                .filter(c -> c.getDepartment() != null && c.getDepartment().getDepartmentId().equals(inv.getArgument(0)))
                .filter(c -> ((Collection<?>) inv.getArgument(1)).contains(c.getStatus())).toList());
        when(complaints.findByStatusIn(anyCollection())).thenAnswer(inv -> db.stream()
                .filter(c -> ((Collection<?>) inv.getArgument(0)).contains(c.getStatus())).toList());
        when(complaints.countByDepartment_DepartmentIdAndStatusIn(anyLong(), anyCollection())).thenAnswer(inv -> db.stream()
                .filter(c -> c.getDepartment() != null && c.getDepartment().getDepartmentId().equals(inv.getArgument(0)))
                .filter(c -> ((Collection<?>) inv.getArgument(1)).contains(c.getStatus())).count());
        when(complaints.countByStatusIn(anyCollection())).thenAnswer(inv -> db.stream()
                .filter(c -> ((Collection<?>) inv.getArgument(0)).contains(c.getStatus())).count());
        when(complaints.findByCreatedAtGreaterThanEqual(any())).thenAnswer(inv -> db.stream()
                .filter(c -> !c.getCreatedAt().isBefore(inv.getArgument(0))).toList());
        when(complaints.findByDepartment_DepartmentIdAndCreatedAtGreaterThanEqual(anyLong(), any())).thenAnswer(inv -> db.stream()
                .filter(c -> c.getDepartment() != null && c.getDepartment().getDepartmentId().equals(inv.getArgument(0)))
                .filter(c -> !c.getCreatedAt().isBefore(inv.getArgument(1))).toList());

        staff = new StaffAssistant(personalDashboard, departmentPerformance, governmentDashboard, complaints, sla,
                Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        assistant = new AssistantService(w.complaintService, w.complaintRepository, w.helpArticleRepository, w.aiServiceClient,
                w.store, new AssistantRateLimiter(1000), staff, false);
    }

    private static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2026, 10, day, hour, 0);
    }

    private static Complaint complaint(long id, ComplaintCategory category, ComplaintStatus status, LocalDateTime createdAt,
                                       LocalDateTime dueAt, Department department, User officer) {
        return Complaint.builder().complaintId(id).referenceNumber("JN-2026-%06d".formatted(id)).category(category)
                .status(status).createdAt(createdAt).updatedAt(createdAt).slaDueAt(dueAt).department(department)
                .assignedOfficer(officer).isEscalated(false).isReopened(false).corroborationCount(1).build();
    }

    private AssistantReply ask(User user, String text, String language) {
        return assistant.handle(user, new AssistantMessageRequest(null, text, language));
    }

    private static List<String> references(AssistantReply r) {
        return r.cards().stream().map(AssistantCard::referenceNumber).filter(Objects::nonNull).toList();
    }

    // ---- overdue: same rule as the dashboard tiles, scoped by role ----

    @Test
    void officerSeesOnlyTheirOwnOverdueWorkAndCanOpenTheTile() {
        AssistantReply r = ask(officer, "aaj kya overdue hai", "EN");

        assertThat(r.intent()).isEqualTo("STAFF_OVERDUE");
        assertThat(r.source()).isEqualTo(AssistantService.SOURCE_RULE);
        assertThat(r.reply()).startsWith("1 of your complaints are past their deadline (SLA). 1 more will reach it soon.")
                .contains("JN-2026-000201 (pothole): 2 days late");
        assertThat(references(r)).containsExactly("JN-2026-000201");
        assertThat(r.suggestedActions().get(0)).isEqualTo(new AssistantAction(AssistantAction.OPEN_TILE, "Open the list", "OVERDUE"));
    }

    @Test
    void departmentHeadSeesTheWholeDepartmentButNoOtherDepartment() {
        AssistantReply r = ask(head, AssistantTexts.get(AssistantTexts.CHIP_OVERDUE, "EN"), "EN");

        assertThat(r.reply()).startsWith("2 open complaints in your department are past their deadline (SLA).")
                .contains("JN-2026-000203 (open manhole): 3 hours late");
        assertThat(references(r)).containsExactly("JN-2026-000201", "JN-2026-000203"); // most late first
        assertThat(r.reply() + r.cards()).doesNotContain("JN-2026-000204").doesNotContain("Water Works");
        verify(complaints, never()).findByStatusIn(anyCollection());
    }

    @Test
    void adminSeesAllDepartmentsWithTheDepartmentOnEachCard() {
        AssistantReply r = ask(admin, "sla breaches", "EN");

        assertThat(r.reply()).startsWith("3 open complaints across all departments are past their deadline (SLA).")
                .contains("JN-2026-000204 (water leakage): 9 days late");
        assertThat(references(r)).containsExactly("JN-2026-000204", "JN-2026-000201", "JN-2026-000203");
        assertThat(r.cards().get(0).departmentName()).isEqualTo("Water Works Patna");
    }

    @Test
    void overdueAnswerInHindi() {
        AssistantReply r = ask(head, "कौन सी शिकायतें देरी से हैं", "HI");
        assertThat(r.reply()).startsWith("आपके विभाग की 2 खुली शिकायतें समय सीमा (SLA) पार कर चुकी हैं।")
                .contains("JN-2026-000201 (गड्ढा): 2 दिन की देरी");
    }

    @Test
    void headWithoutADepartmentIsToldSoAndNothingIsQueried() {
        AssistantReply r = ask(headWithoutDepartment, "what is overdue", "EN");
        assertThat(r.reply()).isEqualTo(AssistantTexts.get(AssistantTexts.NO_DEPARTMENT, "EN"));
        verify(complaints, never()).findByStatusIn(anyCollection());
        verify(complaints, never()).findByDepartment_DepartmentIdAndStatusIn(any(), anyCollection());
    }

    // ---- today ----

    @Test
    void officerTodayIsTheDashboardsTodaysTasks() {
        when(personalDashboard.officerDashboard(officer)).thenReturn(new OfficerDashboardResponse(1, 1, 4, 9, 1, 1, 0, 0,
                6, 30.25, 83.3, List.of(
                new OfficerDashboardResponse.TaskItem(201L, "JN-2026-000201", ComplaintCategory.POTHOLE,
                        ComplaintStatus.ASSIGNED, null, at(17, 12), true),
                new OfficerDashboardResponse.TaskItem(202L, "JN-2026-000202", ComplaintCategory.GARBAGE_OVERFLOW,
                        ComplaintStatus.IN_PROGRESS, null, at(19, 13), false))));

        AssistantReply r = ask(officer, AssistantTexts.get(AssistantTexts.CHIP_TODAY, "EN"), "EN");

        assertThat(r.intent()).isEqualTo("STAFF_TODAY");
        assertThat(r.reply()).startsWith("You have 2 open complaints (1 assigned, 1 in progress). These need action within 24 hours:")
                .contains("JN-2026-000201 (pothole): 2 days late")
                .contains("JN-2026-000202 (garbage): due 19-10-2026 13:00");
        assertThat(references(r)).containsExactly("JN-2026-000201", "JN-2026-000202");
        assertThat(r.suggestedActions().get(0).type()).isEqualTo(AssistantAction.OPEN_MY_COMPLAINTS);
    }

    @Test
    void headAndAdminTodayAreTheirScopeSummaries() {
        assertThat(ask(head, "today", "EN").reply()).isEqualTo(
                "In your department: 1 complaints are waiting for an officer, 4 are open in total and 2 are past their deadline.");
        assertThat(ask(admin, "what should I do today", "EN").reply()).isEqualTo(
                "Across all departments: 2 complaints are waiting for an officer, 6 are open in total and 3 are past their deadline.");
    }

    // ---- waiting for an officer ----

    @Test
    void waitingForAnOfficerIsTheDepartmentHeadsAndAdminsQuestion() {
        AssistantReply forHead = ask(head, "assign nahi hua", "EN");
        assertThat(forHead.reply()).startsWith("1 complaints are waiting for an officer. Oldest first:")
                .contains("JN-2026-000205 (pothole): reported on 12-10-2026");
        assertThat(references(forHead)).containsExactly("JN-2026-000205");
        assertThat(forHead.suggestedActions().get(0).type()).isEqualTo(AssistantAction.OPEN_MY_COMPLAINTS);

        assertThat(references(ask(admin, "unassigned complaints", "EN"))).containsExactly("JN-2026-000205", "JN-2026-000206");

        AssistantReply forOfficer = ask(officer, "unassigned complaints", "EN");
        assertThat(forOfficer.reply()).isEqualTo(AssistantTexts.get(AssistantTexts.ONLY_HEAD, "EN"));
        assertThat(forOfficer.cards()).isEmpty();
    }

    // ---- workload ----

    @Test
    void headSeesOfficerWorkloadOfOwnDepartmentMostFirst() {
        when(departmentPerformance.getPerformance(head, 3L)).thenReturn(new DepartmentPerformanceResponse(3L,
                "Public Works Gaya", 10L, 4L, 5L, 1L, 90.0, 20.0, List.of(
                new OfficerWorkloadResponse(70L, "Ravi Kumar", 1L, 3L, 10.0, 0L),
                new OfficerWorkloadResponse(7L, "Ramesh Singh", 2L, 4L, 12.0, 1L)), NOW));

        AssistantReply r = ask(head, AssistantTexts.get(AssistantTexts.CHIP_WORKLOAD, "EN"), "EN");

        assertThat(r.reply()).isEqualTo("Open work per officer in Public Works Gaya, most first:\n"
                + "• Ramesh Singh: 2 open, 1 escalated\n• Ravi Kumar: 1 open, 0 escalated");
        verify(departmentPerformance).getPerformance(head, 3L); // own department only
    }

    @Test
    void adminSeesOpenComplaintsPerDepartmentAndAnOfficerIsPointedToTheHead() {
        when(governmentDashboard.getDepartmentComparison()).thenReturn(List.of(
                new DepartmentComparisonResponse(3L, "Public Works Gaya", 10L, 4L, 5L, 90.0),
                new DepartmentComparisonResponse(4L, "Water Works Patna", 6L, 5L, 1L, 70.0)));

        assertThat(ask(admin, "department wise workload", "EN").reply()).isEqualTo(
                "Open complaints per department, most first:\n• Water Works Patna: 5 open of 6\n• Public Works Gaya: 4 open of 10");
        assertThat(ask(officer, "which officer has the most work", "EN").reply())
                .isEqualTo(AssistantTexts.get(AssistantTexts.ONLY_HEAD, "EN"));
        verify(departmentPerformance, never()).getPerformance(eq(officer), any());
    }

    // ---- this week's numbers ----

    @Test
    void weeklyNumbersAreScopedByRole() {
        AssistantReply forHead = ask(head, "is hafte ke aankde", "EN");
        assertThat(forHead.reply()).isEqualTo("In the last 7 days your department received 3 new complaints. Their status now:\n"
                + "• Assigned: 2\n• In Progress: 1");

        AssistantReply forAdmin = ask(admin, AssistantTexts.get(AssistantTexts.CHIP_STATS, "EN"), "EN");
        assertThat(forAdmin.reply()).isEqualTo("In the last 7 days 5 new complaints were received. Their status now:\n"
                + "• Verified: 1\n• Assigned: 2\n• In Progress: 1\n• Rejected: 1\n\n"
                + "By department:\n• Public Works Gaya: 3\n• Water Works Patna: 1");
    }

    @Test
    void officerNumbersComeFromTheirDashboard() {
        when(personalDashboard.officerDashboard(officer)).thenReturn(
                new OfficerDashboardResponse(1, 1, 4, 9, 1, 1, 0, 0, 6, 30.25, 90.0, List.of()));
        assertThat(ask(officer, "this week", "EN").reply()).isEqualTo("Your work now: 1 assigned, 1 in progress. "
                + "In the last 30 days you resolved 6 complaints. Resolved within the deadline: 90%. "
                + "Average time to resolve: 30.25 hours.");
    }

    // ---- staff menu and citizens ----

    @Test
    void eachRoleGetsItsOwnQuickButtons() {
        assertThat(ask(head, "hi", "EN").suggestedActions()).extracting(AssistantAction::label).containsExactly(
                "Waiting for an officer", "What is overdue?", "Officer workload", "This week's numbers");
        assertThat(ask(admin, "hi", "EN").suggestedActions()).extracting(AssistantAction::label).containsExactly(
                "This week's numbers", "What is overdue?", "Waiting for an officer", "Officer workload");
    }

    @Test
    void aCitizenNeverGetsStaffAnswers() {
        AssistantReply r = ask(citizen, "Officer workload", "EN");
        assertThat(r.intent()).isEqualTo("MY_COMPLAINTS");
        verify(complaints, never()).findByStatusIn(anyCollection());
        verify(governmentDashboard, never()).getDepartmentComparison();
    }

    @Test
    void lateIsWrittenInHoursOrDays() {
        assertThat(StaffAssistant.late(java.time.Duration.ofMinutes(90), "EN")).isEqualTo("under 2 hours");
        assertThat(StaffAssistant.late(java.time.Duration.ofHours(30), "EN")).isEqualTo("30 hours");
        assertThat(StaffAssistant.late(java.time.Duration.ofHours(75), "HI")).isEqualTo("3 दिन");
    }
}
