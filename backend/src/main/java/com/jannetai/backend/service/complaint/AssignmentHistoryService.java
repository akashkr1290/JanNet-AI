package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.ComplaintAssignment;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.repository.ComplaintAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * V36: keeps the assignment history (complaint_assignments) - spec
 * 2026-10-11 "keep an assignment history so that reassignment and complaint
 * tracking remain possible".
 *
 * <p>Written from DepartmentAssignmentService#routeToDepartment (automatic
 * routing: RULE / AREA_RULE / FALLBACK) and ComplaintService#reassign
 * (MANUAL). Joins the caller's transaction, so a rolled-back routing leaves
 * no history row either.
 */
@Service
@RequiredArgsConstructor
public class AssignmentHistoryService {

    private final ComplaintAssignmentRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Complaint complaint, Department department, User officer, String method, Long routingRuleId,
                       String reason, User assignedBy) {
        repository.save(ComplaintAssignment.builder()
                .complaint(complaint)
                .department(department)
                .officer(officer)
                .localBodyCode(complaint.getLocation() != null ? complaint.getLocation().getLocalBodyCode() : null)
                .ward(complaint.getLocation() != null ? complaint.getLocation().getWard() : null)
                .routingRuleId(routingRuleId)
                .method(method)
                .reason(reason == null ? null : reason.length() > 300 ? reason.substring(0, 300) : reason)
                .assignedBy(assignedBy)
                .build());
    }

    @Transactional(readOnly = true)
    public List<ComplaintAssignment> history(Long complaintId) {
        return repository.findByComplaintComplaintIdOrderByAssignmentIdAsc(complaintId);
    }
}
