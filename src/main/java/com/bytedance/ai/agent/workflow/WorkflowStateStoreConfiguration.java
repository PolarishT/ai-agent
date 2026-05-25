package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 默认绑定 {@link JdbcWorkflowStateStore}（生产路径）；
 * 当没有 {@link JdbcTemplate} 时（如纯本地/单测）回退到 {@link InMemoryWorkflowStateStore}。
 */
@Configuration
public class WorkflowStateStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkflowStateStore.class)
    public WorkflowStateStore workflowStateStore(
            org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbcProvider,
            RagJsonCodec jsonCodec
    ) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            return new JdbcWorkflowStateStore(jdbc, jsonCodec);
        }
        return new InMemoryWorkflowStateStore();
    }
}
