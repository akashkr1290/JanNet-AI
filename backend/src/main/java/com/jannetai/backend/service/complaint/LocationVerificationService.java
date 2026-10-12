package com.jannetai.backend.service.complaint;

import com.jannetai.backend.dto.complaint.ComplaintAssignmentResponse;
import com.jannetai.backend.dto.complaint.LocationVerificationItem;
import com.jannetai.backend.dto.complaint.LocationVerificationRequest;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.ComplaintAssignment;
import com.jannetai.backend.entity.Location;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.exception.ResourceNotFoundException;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * V36 (spec 2026-10-11): the complaint's assignment view and the manual
 * location verification queue.
 *
 * <ul>
 *   <li>{@link #assignment} - GET /complaints/{id}/assignment: department,
 *       whether an officer is assigned, the area and the history
 *       (complaint_assignments). Same visibility as the complaint itself
 *       (ComplaintService#getDetail does the scope check).</li>
 *   <li>{@link #queue} - GET /complaints/location-verification: open complaints
 *       whose ward / area could not be proven by a published boundary
 *       (LocationService#applyArea set REQUIRES_VERIFICATION). Department Heads
 *       see their department, officers their own complaints, Verification
 *       Team / Admin everything.</li>
 *   <li>{@link #verify} - PATCH /complaints/{id}/location-verification: staff
 *       confirm or correct the ward (LocationService#verifyByStaff).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LocationVerificationService {

    private static final Set<ComplaintStatus> CLOSED = EnumSet.of(ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED,
            ComplaintStatus.REJECTED, ComplaintStatus.DUPLICATE, ComplaintStatus.DRAFT);

    private final ComplaintService complaintService;
    private final ComplaintRepository complaintRepository;
    private final AssignmentHistoryService assignmentHistory;
    private final LocationService locationService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public ComplaintAssignmentResponse assignment(User requester, Long complaintId) {
        complaintService.getDetail(requester, complaintId); // scope check (403 / 404)
        Complaint c = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));
        boolean staff = requester.getRole() != Role.CITIZEN;
        Location l = c.getLocation();
        List<ComplaintAssignmentResponse.Entry> entries = assignmentHistory.history(complaintId).stream()
                .map(a -> entry(a, staff)).toList();
        return new ComplaintAssignmentResponse(
                c.getComplaintId(), c.getReferenceNumber(), c.getStatus(),
                c.getDepartment() != null ? c.getDepartment().getDepartmentId() : null,
                c.getDepartment() != null ? c.getDepartment().getName() : null,
                c.getAssignedOfficer() != null,
                staff && c.getAssignedOfficer() != null ? c.getAssignedOfficer().getFullName() : null,
                l != null && l.getLocalBody() != null ? l.getLocalBody().getName() : null,
                l != null && l.getWard() != null ? l.getWard().getName() : null,
                l != null ? l.getLocationVerificationStatus() : null,
                l != null ? l.getVerificationReason() : null,
                entries);
    }

    private static ComplaintAssignmentResponse.Entry entry(ComplaintAssignment a, boolean staff) {
        return new ComplaintAssignmentResponse.Entry(a.getAssignedAt(),
                a.getDepartment() != null ? a.getDepartment().getName() : null,
                staff && a.getOfficer() != null ? a.getOfficer().getFullName() : null,
                a.getMethod(),
                staff ? a.getReason() : null);
    }

    @Transactional(readOnly = true)
    public Page<LocationVerificationItem> queue(User staff, int page, int pageSize) {
        Long departmentId = null;
        Long officerId = null;
        if (staff.getRole() == Role.DEPARTMENT_HEAD) {
            if (staff.getDepartment() == null) {
                return Page.empty();
            }
            departmentId = staff.getDepartment().getDepartmentId();
        } else if (staff.getRole() == Role.GOVERNMENT_OFFICER) {
            officerId = staff.getUserId();
        }
        int size = Math.max(1, Math.min(pageSize, 100));
        return complaintRepository.findLocationVerificationQueue(CLOSED, departmentId, officerId,
                PageRequest.of(Math.max(0, page), size)).map(LocationVerificationService::item);
    }

    private static LocationVerificationItem item(Complaint c) {
        Location l = c.getLocation();
        return new LocationVerificationItem(c.getComplaintId(), c.getReferenceNumber(), c.getCategory(), c.getStatus(),
                c.getDepartment() != null ? c.getDepartment().getName() : null,
                l.getLocalBodyCode(), l.getLocalBody() != null ? l.getLocalBody().getName() : null,
                l.getWard() != null ? l.getWard().getWardId() : null, l.getWard() != null ? l.getWard().getName() : null,
                l.getSuggestedWard() != null ? l.getSuggestedWard().getWardId() : null,
                l.getSuggestedWard() != null ? l.getSuggestedWard().getName() : null,
                l.getVerificationReason(), l.getLatitude(), l.getLongitude(), c.getCreatedAt());
    }

    @Transactional
    public ComplaintAssignmentResponse verify(User staff, Long complaintId, LocationVerificationRequest request) {
        complaintService.getDetail(staff, complaintId); // scope check
        Complaint c = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new ResourceNotFoundException("Complaint not found: " + complaintId));
        if (c.getLocation() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This complaint has no location to verify");
        }
        Location before = c.getLocation();
        Long oldWard = before.getWard() != null ? before.getWard().getWardId() : null;
        String oldReason = before.getVerificationReason();
        locationService.verifyByStaff(before, request.wardId());
        String note = request.note() == null || request.note().isBlank() ? null
                : request.note().strip().replace("\"", "'");
        auditService.record(staff, "COMPLAINT_LOCATION_VERIFIED", "COMPLAINT", complaintId,
                "{\"old_ward_id\":" + oldWard + ",\"ward_id\":" + request.wardId()
                        + ",\"old_reason\":" + (oldReason == null ? "null" : "\"" + oldReason + "\"")
                        + (note == null ? "" : ",\"note\":\"" + (note.length() > 300 ? note.substring(0, 300) : note) + "\"")
                        + "}");
        return assignment(staff, complaintId);
    }
}
