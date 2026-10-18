package com.jannetai.backend.service.assistant;

import com.jannetai.backend.repository.AssistantSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * JanNet Sahayak: conversations are kept {@code app.assistant.retention-days}
 * (default 90) after their last message, then deleted with their messages
 * (V38 ON DELETE CASCADE). Runs daily at 03:30 (Asia/Kolkata).
 */
@Component
public class AssistantRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AssistantRetentionJob.class);

    private final AssistantSessionRepository sessionRepository;
    private final int retentionDays;

    public AssistantRetentionJob(AssistantSessionRepository sessionRepository,
                                 @Value("${app.assistant.retention-days:90}") int retentionDays) {
        this.sessionRepository = sessionRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${app.assistant.retention-cron:0 30 3 * * *}", zone = "${app.reports.zone:Asia/Kolkata}")
    @Transactional
    public void purge() {
        int deleted = sessionRepository.deleteInactiveSince(LocalDateTime.now().minusDays(Math.max(1, retentionDays)));
        if (deleted > 0) {
            log.info("Assistant retention: deleted {} conversation(s) older than {} days", deleted, retentionDays);
        }
    }
}
