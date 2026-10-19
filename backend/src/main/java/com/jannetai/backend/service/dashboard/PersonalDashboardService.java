package com.jannetai.backend.service.dashboard;

import com.jannetai.backend.dto.complaint.AiClassificationResponse;
import com.jannetai.backend.dto.complaint.ComplaintSummaryResponse;
import com.jannetai.backend.dto.dashboard.CitizenDashboardResponse;
import com.jannetai.backend.dto.dashboard.OfficerDashboardResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Prediction;
import com.jannetai.backend.entity.StatusHistory;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.PredictionRepository;
import com.jannetai.backend.repository.StatusHistoryRepository;
import com.jannetai.backend.service.department.SlaPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gap-backlog Patches 08 and 09 (Sep 2026 strict recheck) - the two P0
 * dashboards that did not exist anywhere in the codebase before this pass.
 * Strictly self-scoped: every query keys on the caller's own userId (never a
 * request parameter), so there is no IDOR surface to get wrong.
 */
@Service
@RequiredArgsConstructor
public class PersonalDashboardService {

    private static final EnumSet<ComplaintStatus> PENDING = EnumSet.of(
            ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING,
            ComplaintStatus.VERIFIED, ComplaintStatus.ASSIGNED);
    private static final EnumSet<ComplaintStatus> OFFICER_OPEN = EnumSet.of(
            ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
    private static final double AT_RISK_FRACTION = 0.8;
    private static final int PERFORMANCE_WINDOW_DAYS = 30;
    private static final int MAX_TASKS = 10;

    private final ComplaintRepository complaintRepository;
    private final PredictionRepository predictionRepository;
    private final StatusHistoryRepository statusHistoryRepository;
    private final SlaPolicy slaPolicy;

    // ------------------------------ Patch 08 ------------------------------

    @Transactional(readOnly = true)
    public CitizenDashboardResponse citizenDashboard(User citizen) {
        Map<ComplaintStatus, Long> counts = toCounts(complaintRepository.countByStatusForCitizen(citizen.getUserId()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long pending = PENDING.stream().mapToLong(s -> counts.getOrDefault(s, 0L)).sum();

        List<CitizenDashboardResponse.RecentComplaint> recent = complaintRepository
                .findTop5ByCitizen_UserIdOrderByCreatedAtDesc(citizen.getUserId())
                .stream().map(this::toRecent).toList();

        return new CitizenDashboardResponse(
                total,
                pending,
                counts.getOrDefault(ComplaintStatus.IN_PROGRESS, 0L),
                counts.getOrDefault(ComplaintStatus.RESOLVED, 0L),
                counts.getOrDefault(ComplaintStatus.CLOSED, 0L),
                counts.getOrDefault(ComplaintStatus.REJECTED, 0L),
                counts.getOrDefault(ComplaintStatus.DUPLICATE, 0L),
                recent);
    }

    private CitizenDashboardResponse.RecentComplaint toRecent(Complaint c) {
        Prediction prediction = predictionRepository
                .findFirstByComplaint_ComplaintIdOrderByCreatedAtDescPredictionIdDesc(c.getComplaintId())
                .orElse(null);
        AiClassificationResponse ai = prediction != null ? AiClassificationResponse.from(prediction) : null;
        return new CitizenDashboardResponse.RecentComplaint(
                c.getComplaintId(),
                c.getReferenceNumber(),
                c.getCategory(),
                c.getStatus(),
                c.getSeverity(),
                prediction != null ? prediction.getPriorityScore() : null,
                ai != null ? ai.aiStatus() : null,
                ai != null ? ai.confidence() : null,
                c.getCreatedAt(),
                slaPolicy.dueAt(c),
                Boolean.TRUE.equals(c.getIsEscalated()));
    }

    // ------------------------------ Patch 09 ------------------------------

    @Transactional(readOnly = true)
    public OfficerDashboardResponse officerDashboard(User officer) {
        Long officerId = officer.getUserId();
        Map<ComplaintStatus, Long> counts = toCounts(complaintRepository.countByStatusForOfficer(officerId));
        LocalDateTime now = LocalDateTime.now();

        long overdue = 0;
        long atRisk = 0;
        long onTrack = 0;
        long noSla = 0;
        List<Complaint> open = complaintRepository.findByAssignedOfficer_UserIdAndStatusIn(officerId, OFFICER_OPEN);
        for (Complaint c : open) {
            switch (slaBucket(c, now)) {
                case NO_SLA -> noSla++;
                case OVERDUE -> overdue++;
                case AT_RISK -> atRisk++;
                case ON_TRACK -> onTrack++;
            }
        }

        // "Today's tasks": open work that is overdue or falls due within the
        // next 24 hours, most urgent first.
        LocalDateTime horizon = now.plusHours(24);
        List<OfficerDashboardResponse.TaskItem> tasks = open.stream()
                .filter(c -> {
                    LocalDateTime due = slaPolicy.dueAt(c);
                    return due != null && !due.isAfter(horizon);
                })
                .sorted(Comparator.comparing(slaPolicy::dueAt))
                .limit(MAX_TASKS)
                .map(c -> {
                    LocalDateTime due = slaPolicy.dueAt(c);
                    return new OfficerDashboardResponse.TaskItem(c.getComplaintId(), c.getReferenceNumber(),
                            c.getCategory(), c.getStatus(), c.getSeverity(), due, now.isAfter(due));
                })
                .toList();

        // Personal performance from status_history: who resolved it, and when.
        List<StatusHistory> resolutions = statusHistoryRepository
                .findByActor_UserIdAndNewStatusAndChangedAtGreaterThanEqual(
                        officerId, ComplaintStatus.RESOLVED, now.minusDays(PERFORMANCE_WINDOW_DAYS));
        long totalMinutes = 0;
        int withSla = 0;
        int withinSla = 0;
        for (StatusHistory h : resolutions) {
            Complaint c = h.getComplaint();
            totalMinutes += Duration.between(c.getCreatedAt(), h.getChangedAt()).toMinutes();
            LocalDateTime due = slaPolicy.dueAt(c);
            if (due != null) {
                withSla++;
                if (!h.getChangedAt().isAfter(due)) {
                    withinSla++;
                }
            }
        }
        Double avgHours = resolutions.isEmpty() ? null
                : Math.round(totalMinutes / 60.0 / resolutions.size() * 10.0) / 10.0;
        Double compliance = withSla == 0 ? null : Math.round(1000.0 * withinSla / withSla) / 10.0;

        return new OfficerDashboardResponse(
                counts.getOrDefault(ComplaintStatus.ASSIGNED, 0L),
                counts.getOrDefault(ComplaintStatus.IN_PROGRESS, 0L),
                counts.getOrDefault(ComplaintStatus.RESOLVED, 0L),
                counts.getOrDefault(ComplaintStatus.CLOSED, 0L),
                overdue, atRisk, onTrack, noSla,
                resolutions.size(), avgHours, compliance, tasks);
    }

    // --------------------- dashboard tiles -> their complaints ---------------------

    /**
     * The SLA groups of the officer dashboard - one rule for the counts, the
     * tile lists and JanNet Sahayak's "what is overdue" answers.
     */
    public enum SlaBucket { OVERDUE, AT_RISK, ON_TRACK, NO_SLA }

    public SlaBucket slaBucket(Complaint c, LocalDateTime now) {
        LocalDateTime due = slaPolicy.dueAt(c);
        if (due == null) {
            return SlaBucket.NO_SLA;
        }
        if (now.isAfter(due)) {
            return SlaBucket.OVERDUE;
        }
        long window = Duration.between(c.getCreatedAt(), due).toMinutes();
        long elapsed = Duration.between(c.getCreatedAt(), now).toMinutes();
        return window > 0 && elapsed >= window * AT_RISK_FRACTION ? SlaBucket.AT_RISK : SlaBucket.ON_TRACK;
    }

    /** Citizen dashboard tiles (same groups as {@link #citizenDashboard}). */
    public static final Set<String> CITIZEN_TILES = Set.of(
            "TOTAL", "PENDING", "IN_PROGRESS", "RESOLVED", "CLOSED", "REJECTED", "DUPLICATE");
    /** Officer dashboard tiles (same groups as {@link #officerDashboard}). */
    public static final Set<String> OFFICER_TILES = Set.of(
            "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "OVERDUE", "AT_RISK", "ON_TRACK", "NO_SLA");

    /**
     * GET /api/v1/my/complaints?tile=... - the complaints behind one tile of the
     * caller's OWN dashboard (citizen: their complaints; officer / department
     * head: complaints assigned to them), with exactly the conditions the tile
     * counts with. No id parameter: it can only ever show the caller's own work.
     * Status tiles are newest first; SLA tiles are most urgent first.
     */
    @Transactional(readOnly = true)
    public Page<ComplaintSummaryResponse> tileComplaints(User user, String tile, int page, int pageSize) {
        String t = tile == null ? "" : tile.trim().toUpperCase(Locale.ROOT);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Pageable newest = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Long userId = user.getUserId();

        if (user.getRole() == com.jannetai.backend.entity.enums.Role.CITIZEN) {
            if (!CITIZEN_TILES.contains(t)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown dashboard tile: " + tile);
            }
            Page<Complaint> result = "TOTAL".equals(t)
                    ? complaintRepository.findByCitizen_UserId(userId, newest)
                    : complaintRepository.findByCitizen_UserIdAndStatusIn(userId,
                            "PENDING".equals(t) ? PENDING : EnumSet.of(ComplaintStatus.valueOf(t)), newest);
            return result.map(ComplaintSummaryResponse::from);
        }

        if (!OFFICER_TILES.contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown dashboard tile: " + tile);
        }
        if (Set.of("ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED").contains(t)) {
            return complaintRepository.findByAssignedOfficer_UserIdAndStatusIn(userId,
                    EnumSet.of(ComplaintStatus.valueOf(t)), newest).map(ComplaintSummaryResponse::from);
        }
        // SLA tiles: classified in memory with the same rule as the counts, most urgent first.
        SlaBucket bucket = SlaBucket.valueOf(t);
        LocalDateTime now = LocalDateTime.now();
        List<Complaint> matching = complaintRepository.findByAssignedOfficer_UserIdAndStatusIn(userId, OFFICER_OPEN)
                .stream()
                .filter(c -> slaBucket(c, now) == bucket)
                .sorted(Comparator.comparing((Complaint c) -> slaPolicy.dueAt(c),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Complaint::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min(safePage * safeSize, matching.size());
        int to = Math.min(from + safeSize, matching.size());
        return new PageImpl<>(matching.subList(from, to).stream().map(ComplaintSummaryResponse::from).toList(),
                PageRequest.of(safePage, safeSize), matching.size());
    }

    private static Map<ComplaintStatus, Long> toCounts(List<Object[]> rows) {
        Map<ComplaintStatus, Long> counts = new EnumMap<>(ComplaintStatus.class);
        for (Object[] row : rows) {
            counts.put((ComplaintStatus) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }
}
