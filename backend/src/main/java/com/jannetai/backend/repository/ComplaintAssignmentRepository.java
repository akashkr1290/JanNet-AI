package com.jannetai.backend.repository;

import com.jannetai.backend.entity.ComplaintAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** V36 assignment history (AssignmentHistoryService). */
@Repository
public interface ComplaintAssignmentRepository extends JpaRepository<ComplaintAssignment, Long> {

    List<ComplaintAssignment> findByComplaintComplaintIdOrderByAssignmentIdAsc(Long complaintId);
}
