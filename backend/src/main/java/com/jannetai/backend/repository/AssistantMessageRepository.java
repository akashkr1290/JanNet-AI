package com.jannetai.backend.repository;

import com.jannetai.backend.entity.AssistantMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** JanNet Sahayak messages (V38) - written only; read for audits directly in the database. */
@Repository
public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {
}
