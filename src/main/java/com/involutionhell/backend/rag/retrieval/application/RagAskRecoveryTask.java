package com.involutionhell.backend.rag.retrieval.application;

import com.involutionhell.backend.rag.retrieval.persistence.RagStaleAskRunRecord;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 补偿异常中断后未能及时标记失败的问答流。
 */
@Component
@ConditionalOnProperty(prefix = "rag.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagAskRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(RagAskRecoveryTask.class);

    private final RagProperties ragProperties;
    private final RagConversationService conversationService;

    public RagAskRecoveryTask(RagProperties ragProperties, RagConversationService conversationService) {
        this.ragProperties = ragProperties;
        this.conversationService = conversationService;
    }

    @Scheduled(fixedDelayString = "${rag.recovery.fixed-delay-millis:60000}")
    public void recoverStaleRunningAsks() {
        OffsetDateTime threshold = OffsetDateTime.now()
                .minusNanos(ragProperties.recovery().processingStaleMillis() * 1_000_000L);
        List<RagStaleAskRunRecord> staleRuns = conversationService.findStaleRunningAsks(
                threshold,
                ragProperties.recovery().batchSize()
        );
        int recovered = 0;
        for (RagStaleAskRunRecord staleRun : staleRuns) {
            try {
                conversationService.recoverStaleRunningAsk(staleRun);
                recovered++;
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to recover stale RAG ask run: runId={}, startedAt={}, error={}",
                        staleRun.runId(),
                        staleRun.startedAt(),
                        RagLogHelper.errorSummary(exception),
                        exception
                );
            }
        }
        if (recovered > 0) {
            log.warn("Recovered stale RAG ask runs: count={}", recovered);
        }
    }
}
