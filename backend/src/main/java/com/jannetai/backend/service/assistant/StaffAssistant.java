package com.jannetai.backend.service.assistant;

import com.jannetai.backend.dto.assistant.AssistantAction;
import com.jannetai.backend.dto.assistant.AssistantCard;
import com.jannetai.backend.dto.dashboard.DepartmentComparisonResponse;
import com.jannetai.backend.dto.dashboard.OfficerDashboardResponse;
import com.jannetai.backend.dto.department.DepartmentPerformanceResponse;
import com.jannetai.backend.dto.department.OfficerWorkloadResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.assistant.AssistantService.Outcome;
import com.jannetai.backend.service.assistant.IntentRouter.Intent;
import com.jannetai.backend.service.dashboard.GovernmentDashboardService;
import com.jannetai.backend.service.dashboard.PersonalDashboardService;
import com.jannetai.backend.service.dashboard.PersonalDashboardService.SlaBucket;
import com.jannetai.backend.service.department.DepartmentPerformanceService;
import com.jannetai.backend.service.department.SlaPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JanNet Sahayak v2 - answers to staff work questions (feature plan, Feature A,
 * "What it does, by role" v2 rows).
 *
 * <p>Read-only and scoped exactly like the staff screens the numbers come from:
 * <ul>
 *   <li>Government Officer: only complaints assigned to them (the officer
 *       dashboard and its tiles);</li>
 *   <li>Department Head: only their own department (the department queue,
 *       Department Performance);</li>
 *   <li>Admin / Super Admin: all departments (the admin dashboard).</li>
 * </ul>
 * The department always comes from the signed-in account, never from the
 * message. "Overdue" uses the officer dashboard's own SLA rule
 * ({@link PersonalDashboardService#slaBucket}), so the assistant and the
 * dashboard tiles always give the same count. No answer here uses Gemini.
 */
@Component
public class StaffAssistant {

    static final int LIST_SIZE = 5;
    static final int TABLE_SIZE = 8;
    static final int STATS_DAYS = 7;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** Routed to a department, waiting for the Department Head to assign an officer. */
    private static final Set<ComplaintStatus> WAITING = EnumSet.of(ComplaintStatus.VERIFIED);
    /** Statuses with a running SLA clock - the officer dashboard's open work. */
    private static final Set<ComplaintStatus> CLOCKED = EnumSet.of(ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
    /** Open work, as on the government dashboard and Department Performance. */
    private static final Set<ComplaintStatus> OPEN = EnumSet.of(ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED,
            ComplaintStatus.IN_PROGRESS);

    private final PersonalDashboardService personalDashboardService;
    private final DepartmentPerformanceService departmentPerformanceService;
    private final GovernmentDashboardService governmentDashboardService;
    private final ComplaintRepository complaintRepository;
    private final SlaPolicy slaPolicy;
    private final Clock clock;

    @Autowired
    public StaffAssistant(PersonalDashboardService personalDashboardService,
                          DepartmentPerformanceService departmentPerformanceService,
                          GovernmentDashboardService governmentDashboardService,
                          ComplaintRepository complaintRepository, SlaPolicy slaPolicy) {
        this(personalDashboardService, departmentPerformanceService, governmentDashboardService, complaintRepository,
                slaPolicy, Clock.systemDefaultZone());
    }

    StaffAssistant(PersonalDashboardService personalDashboardService,
                   DepartmentPerformanceService departmentPerformanceService,
                   GovernmentDashboardService governmentDashboardService,
                   ComplaintRepository complaintRepository, SlaPolicy slaPolicy, Clock clock) {
        this.personalDashboardService = personalDashboardService;
        this.departmentPerformanceService = departmentPerformanceService;
        this.governmentDashboardService = governmentDashboardService;
        this.complaintRepository = complaintRepository;
        this.slaPolicy = slaPolicy;
        this.clock = clock;
    }

    /** Officer, Department Head, Admin or Super Admin - the accounts these answers are for. */
    public static boolean isStaff(User user) {
        Role r = user.getRole();
        return r == Role.GOVERNMENT_OFFICER || r == Role.DEPARTMENT_HEAD || r == Role.ADMIN || r == Role.SUPER_ADMIN;
    }

    static boolean isAdmin(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
    }

    // ---------------------------------------------------------------- overdue

    @Transactional(readOnly = true)
    public Outcome overdue(User user, String language) {
        List<Complaint> open;
        String headKey;
        switch (user.getRole()) {
            case GOVERNMENT_OFFICER -> {
                open = complaintRepository.findByAssignedOfficer_UserIdAndStatusIn(user.getUserId(), CLOCKED);
                headKey = AssistantTexts.OVERDUE_MINE;
            }
            case DEPARTMENT_HEAD -> {
                Long department = ownDepartmentId(user);
                if (department == null) {
                    return noDepartment(Intent.STAFF_OVERDUE, user, language);
                }
                open = complaintRepository.findByDepartment_DepartmentIdAndStatusIn(department, CLOCKED);
                headKey = AssistantTexts.OVERDUE_DEPARTMENT;
            }
            default -> {
                open = complaintRepository.findByStatusIn(CLOCKED);
                headKey = AssistantTexts.OVERDUE_ALL;
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Sla sla = classify(open, now);
        List<AssistantAction> actions = new ArrayList<>();
        if (sla.overdue().isEmpty()) {
            actions.addAll(AssistantService.menu(user, language));
            return new Outcome(Intent.STAFF_OVERDUE, AssistantTexts.get(AssistantTexts.OVERDUE_NONE, language, sla.atRisk()),
                    AssistantService.SOURCE_RULE, List.of(), actions, List.of("sla_overdue"), null);
        }
        StringBuilder reply = new StringBuilder(AssistantTexts.get(headKey, language, sla.overdue().size(), sla.atRisk()));
        List<Complaint> top = sla.overdue().subList(0, Math.min(LIST_SIZE, sla.overdue().size()));
        for (Complaint c : top) {
            reply.append("\n• ").append(AssistantTexts.get(AssistantTexts.OVERDUE_ITEM, language, c.getReferenceNumber(),
                    AssistantTexts.category(c.getCategory(), language),
                    late(Duration.between(slaPolicy.dueAt(c), now), language)));
        }
        if (user.getRole() == Role.GOVERNMENT_OFFICER) {
            // the officer dashboard's Overdue tile shows exactly this list
            actions.add(new AssistantAction(AssistantAction.OPEN_TILE,
                    AssistantTexts.get(AssistantTexts.ACTION_OPEN_LIST, language), "OVERDUE"));
        }
        actions.addAll(AssistantService.menu(user, language));
        return new Outcome(Intent.STAFF_OVERDUE, reply.toString(), AssistantService.SOURCE_RULE,
                cards(top, user), actions, List.of("sla_overdue"), null);
    }

    // ------------------------------------------------------------------ today

    @Transactional(readOnly = true)
    public Outcome today(User user, String language) {
        if (user.getRole() == Role.GOVERNMENT_OFFICER) {
            return officerToday(user, language);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long waiting;
        long open;
        Sla sla;
        String key;
        if (user.getRole() == Role.DEPARTMENT_HEAD) {
            Long department = ownDepartmentId(user);
            if (department == null) {
                return noDepartment(Intent.STAFF_TODAY, user, language);
            }
            waiting = complaintRepository.countByDepartment_DepartmentIdAndStatusIn(department, WAITING);
            open = complaintRepository.countByDepartment_DepartmentIdAndStatusIn(department, OPEN);
            sla = classify(complaintRepository.findByDepartment_DepartmentIdAndStatusIn(department, CLOCKED), now);
            key = AssistantTexts.TODAY_DEPARTMENT;
        } else {
            waiting = complaintRepository.countByStatusIn(WAITING);
            open = complaintRepository.countByStatusIn(OPEN);
            sla = classify(complaintRepository.findByStatusIn(CLOCKED), now);
            key = AssistantTexts.TODAY_ALL;
        }
        return new Outcome(Intent.STAFF_TODAY, AssistantTexts.get(key, language, waiting, open, sla.overdue().size()),
                AssistantService.SOURCE_RULE, List.of(), AssistantService.menu(user, language),
                List.of("work_summary"), null);
    }

    /** The officer dashboard's "Today's tasks": open work overdue or due within 24 hours, most urgent first. */
    private Outcome officerToday(User user, String language) {
        OfficerDashboardResponse d = personalDashboardService.officerDashboard(user);
        long open = d.assigned() + d.inProgress();
        List<OfficerDashboardResponse.TaskItem> tasks = d.todaysTasks();
        List<AssistantAction> actions = new ArrayList<>();
        actions.add(queueAction(language));
        actions.addAll(AssistantService.menu(user, language));
        if (tasks.isEmpty()) {
            return new Outcome(Intent.STAFF_TODAY,
                    AssistantTexts.get(AssistantTexts.TODAY_MINE_CLEAR, language, open, d.assigned(), d.inProgress()),
                    AssistantService.SOURCE_RULE, List.of(), actions, List.of("officer_dashboard"), null);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        StringBuilder reply = new StringBuilder(
                AssistantTexts.get(AssistantTexts.TODAY_MINE, language, open, d.assigned(), d.inProgress()));
        List<AssistantCard> cards = new ArrayList<>();
        for (OfficerDashboardResponse.TaskItem t : tasks.subList(0, Math.min(LIST_SIZE, tasks.size()))) {
            String category = AssistantTexts.category(t.category(), language);
            reply.append("\n• ").append(t.overdue()
                    ? AssistantTexts.get(AssistantTexts.OVERDUE_ITEM, language, t.referenceNumber(), category,
                            late(Duration.between(t.slaDueAt(), now), language))
                    : AssistantTexts.get(AssistantTexts.DUE_ITEM, language, t.referenceNumber(), category,
                            DAY_TIME.format(t.slaDueAt())));
            cards.add(AssistantCard.complaint(t.complaintId(), t.referenceNumber(), t.category(), t.status(), null, null, null));
        }
        return new Outcome(Intent.STAFF_TODAY, reply.toString(), AssistantService.SOURCE_RULE, cards, actions,
                List.of("officer_dashboard"), null);
    }

    // ------------------------------------------------------------- unassigned

    @Transactional(readOnly = true)
    public Outcome unassigned(User user, String language) {
        if (user.getRole() == Role.GOVERNMENT_OFFICER) {
            return onlyHead(Intent.STAFF_UNASSIGNED, user, language);
        }
        List<Complaint> waiting;
        if (user.getRole() == Role.DEPARTMENT_HEAD) {
            Long department = ownDepartmentId(user);
            if (department == null) {
                return noDepartment(Intent.STAFF_UNASSIGNED, user, language);
            }
            waiting = complaintRepository.findByDepartment_DepartmentIdAndStatusIn(department, WAITING);
        } else {
            waiting = complaintRepository.findByStatusIn(WAITING);
        }
        List<AssistantAction> actions = new ArrayList<>();
        if (waiting.isEmpty()) {
            actions.addAll(AssistantService.menu(user, language));
            return new Outcome(Intent.STAFF_UNASSIGNED, AssistantTexts.get(AssistantTexts.UNASSIGNED_NONE, language),
                    AssistantService.SOURCE_RULE, List.of(), actions, List.of("waiting_for_officer"), null);
        }
        List<Complaint> oldestFirst = waiting.stream()
                .sorted(Comparator.comparing(Complaint::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(LIST_SIZE)
                .toList();
        StringBuilder reply = new StringBuilder(AssistantTexts.get(AssistantTexts.UNASSIGNED_LINE, language, waiting.size()));
        for (Complaint c : oldestFirst) {
            reply.append("\n• ").append(AssistantTexts.get(AssistantTexts.WAITING_ITEM, language, c.getReferenceNumber(),
                    AssistantTexts.category(c.getCategory(), language),
                    c.getCreatedAt() == null ? "-" : DAY.format(c.getCreatedAt())));
        }
        if (user.getRole() == Role.DEPARTMENT_HEAD) {
            actions.add(queueAction(language)); // the head assigns officers from the department queue
        }
        actions.addAll(AssistantService.menu(user, language));
        return new Outcome(Intent.STAFF_UNASSIGNED, reply.toString(), AssistantService.SOURCE_RULE,
                cards(oldestFirst, user), actions, List.of("waiting_for_officer"), null);
    }

    // --------------------------------------------------------------- workload

    @Transactional(readOnly = true)
    public Outcome workload(User user, String language) {
        if (user.getRole() == Role.GOVERNMENT_OFFICER) {
            return onlyHead(Intent.STAFF_WORKLOAD, user, language);
        }
        StringBuilder reply;
        if (user.getRole() == Role.DEPARTMENT_HEAD) {
            Long department = ownDepartmentId(user);
            if (department == null) {
                return noDepartment(Intent.STAFF_WORKLOAD, user, language);
            }
            DepartmentPerformanceResponse performance = departmentPerformanceService.getPerformance(user, department);
            List<OfficerWorkloadResponse> rows = performance.officerWorkloads().stream()
                    .sorted(Comparator.comparing((OfficerWorkloadResponse w) -> zero(w.assignedOpenCount())).reversed()
                            .thenComparing(w -> w.officerName() == null ? "" : w.officerName()))
                    .limit(TABLE_SIZE)
                    .toList();
            if (rows.isEmpty()) {
                return rule(Intent.STAFF_WORKLOAD, AssistantTexts.get(AssistantTexts.NO_OFFICERS, language), user, language,
                        "department_performance");
            }
            reply = new StringBuilder(AssistantTexts.get(AssistantTexts.WORKLOAD_HEAD, language, performance.departmentName()));
            for (OfficerWorkloadResponse w : rows) {
                reply.append("\n• ").append(AssistantTexts.get(AssistantTexts.WORKLOAD_ITEM, language, w.officerName(),
                        zero(w.assignedOpenCount()), zero(w.slaBreachCount())));
            }
            return rule(Intent.STAFF_WORKLOAD, reply.toString(), user, language, "department_performance");
        }
        List<DepartmentComparisonResponse> rows = governmentDashboardService.getDepartmentComparison().stream()
                .sorted(Comparator.comparing((DepartmentComparisonResponse d) -> zero(d.openComplaints())).reversed()
                        .thenComparing(d -> d.departmentName() == null ? "" : d.departmentName()))
                .limit(TABLE_SIZE)
                .toList();
        if (rows.isEmpty()) {
            return rule(Intent.STAFF_WORKLOAD, AssistantTexts.get(AssistantTexts.NO_DEPARTMENTS, language), user, language,
                    "department_comparison");
        }
        reply = new StringBuilder(AssistantTexts.get(AssistantTexts.WORKLOAD_ADMIN, language));
        for (DepartmentComparisonResponse d : rows) {
            reply.append("\n• ").append(AssistantTexts.get(AssistantTexts.WORKLOAD_DEPARTMENT_ITEM, language,
                    d.departmentName(), zero(d.openComplaints()), zero(d.totalComplaints())));
        }
        return rule(Intent.STAFF_WORKLOAD, reply.toString(), user, language, "department_comparison");
    }

    // ------------------------------------------------------------------ stats

    @Transactional(readOnly = true)
    public Outcome stats(User user, String language) {
        if (user.getRole() == Role.GOVERNMENT_OFFICER) {
            OfficerDashboardResponse d = personalDashboardService.officerDashboard(user);
            StringBuilder reply = new StringBuilder(AssistantTexts.get(AssistantTexts.STATS_MINE, language,
                    d.assigned(), d.inProgress(), d.resolvedLast30Days()));
            if (d.slaCompliancePercentLast30Days() != null) {
                reply.append(' ').append(AssistantTexts.get(AssistantTexts.STATS_ON_TIME, language,
                        number(d.slaCompliancePercentLast30Days())));
            }
            if (d.avgResolutionHoursLast30Days() != null) {
                reply.append(' ').append(AssistantTexts.get(AssistantTexts.STATS_AVG_HOURS, language,
                        number(d.avgResolutionHoursLast30Days())));
            }
            return rule(Intent.STAFF_STATS, reply.toString(), user, language, "officer_dashboard");
        }
        LocalDateTime since = LocalDateTime.now(clock).minusDays(STATS_DAYS);
        List<Complaint> received;
        String key;
        if (user.getRole() == Role.DEPARTMENT_HEAD) {
            Long department = ownDepartmentId(user);
            if (department == null) {
                return noDepartment(Intent.STAFF_STATS, user, language);
            }
            received = complaintRepository.findByDepartment_DepartmentIdAndCreatedAtGreaterThanEqual(department, since);
            key = AssistantTexts.STATS_DEPARTMENT;
        } else {
            received = complaintRepository.findByCreatedAtGreaterThanEqual(since);
            key = AssistantTexts.STATS_ALL;
        }
        if (received.isEmpty()) {
            return rule(Intent.STAFF_STATS, AssistantTexts.get(AssistantTexts.STATS_NONE, language), user, language,
                    "weekly_counts");
        }
        Map<ComplaintStatus, Integer> byStatus = new EnumMap<>(ComplaintStatus.class);
        for (Complaint c : received) {
            byStatus.merge(c.getStatus(), 1, Integer::sum);
        }
        StringBuilder reply = new StringBuilder(AssistantTexts.get(key, language, received.size()));
        byStatus.forEach((status, count) ->
                reply.append("\n• ").append(AssistantTexts.statusLabel(status, language)).append(": ").append(count));
        if (isAdmin(user)) {
            Map<String, Integer> byDepartment = new LinkedHashMap<>();
            for (Complaint c : received) {
                if (c.getDepartment() != null && c.getDepartment().getName() != null) {
                    byDepartment.merge(c.getDepartment().getName(), 1, Integer::sum);
                }
            }
            if (!byDepartment.isEmpty()) {
                reply.append("\n\n").append(AssistantTexts.get(AssistantTexts.STATS_BY_DEPARTMENT, language));
                byDepartment.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                        .limit(TABLE_SIZE)
                        .forEach(e -> reply.append("\n• ").append(e.getKey()).append(": ").append(e.getValue()));
            }
        }
        return rule(Intent.STAFF_STATS, reply.toString(), user, language, "weekly_counts");
    }

    // ---------------------------------------------------------------- helpers

    /** Open complaints past their deadline (most late first) and the number close to it - the dashboard's rule. */
    record Sla(List<Complaint> overdue, long atRisk) {
    }

    private Sla classify(List<Complaint> open, LocalDateTime now) {
        List<Complaint> overdue = new ArrayList<>();
        long atRisk = 0;
        for (Complaint c : open) {
            SlaBucket bucket = personalDashboardService.slaBucket(c, now);
            if (bucket == SlaBucket.OVERDUE) {
                overdue.add(c);
            } else if (bucket == SlaBucket.AT_RISK) {
                atRisk++;
            }
        }
        overdue.sort(Comparator.comparing(slaPolicy::dueAt)); // OVERDUE always has a due time
        return new Sla(overdue, atRisk);
    }

    static String late(Duration late, String language) {
        long hours = Math.max(0, late.toHours());
        if (hours < 2) {
            return AssistantTexts.get(AssistantTexts.LATE_UNDER_2H, language);
        }
        if (hours < 48) {
            return AssistantTexts.get(AssistantTexts.LATE_HOURS, language, hours);
        }
        return AssistantTexts.get(AssistantTexts.LATE_DAYS, language, hours / 24);
    }

    private static List<AssistantCard> cards(List<Complaint> complaints, User user) {
        boolean showDepartment = isAdmin(user); // a head or officer knows their own department
        return complaints.stream()
                .map(c -> AssistantCard.complaint(c.getComplaintId(), c.getReferenceNumber(), c.getCategory(), c.getStatus(),
                        showDepartment && c.getDepartment() != null ? c.getDepartment().getName() : null,
                        Boolean.TRUE.equals(c.getIsEscalated()), c.getUpdatedAt()))
                .toList();
    }

    private static Long ownDepartmentId(User user) {
        return user.getDepartment() == null ? null : user.getDepartment().getDepartmentId();
    }

    static AssistantAction queueAction(String language) {
        return new AssistantAction(AssistantAction.OPEN_MY_COMPLAINTS, AssistantTexts.get(AssistantTexts.ACTION_QUEUE, language),
                null);
    }

    private static Outcome noDepartment(Intent intent, User user, String language) {
        return rule(intent, AssistantTexts.get(AssistantTexts.NO_DEPARTMENT, language), user, language, "account");
    }

    private static Outcome onlyHead(Intent intent, User user, String language) {
        return rule(intent, AssistantTexts.get(AssistantTexts.ONLY_HEAD, language), user, language, null);
    }

    private static Outcome rule(Intent intent, String reply, User user, String language, String tool) {
        return new Outcome(intent, reply, AssistantService.SOURCE_RULE, List.of(), AssistantService.menu(user, language),
                tool == null ? List.of() : List.of(tool), null);
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    /** 87.5 -> "87.5", 90.0 -> "90". */
    private static String number(Double value) {
        return value % 1 == 0 ? String.valueOf(value.longValue()) : String.valueOf(value);
    }
}
