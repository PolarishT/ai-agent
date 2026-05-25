package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class WorkflowDefinitionLoader {

    private static final String DEFAULT_WORKFLOW = "ecommerce-guide-v1";

    private final ResourceLoader resourceLoader;
    private final RagJsonCodec jsonCodec;

    public WorkflowDefinitionLoader(ResourceLoader resourceLoader, RagJsonCodec jsonCodec) {
        this.resourceLoader = resourceLoader;
        this.jsonCodec = jsonCodec;
    }

    public WorkflowDefinition loadDefault() {
        return load(DEFAULT_WORKFLOW);
    }

    public WorkflowDefinition load(String workflowId) {
        try {
            Resource resource = resourceLoader.getResource("classpath:workflows/" + workflowId + ".json");
            String json = resource.getContentAsString(StandardCharsets.UTF_8);
            return jsonCodec.read(json, WorkflowDefinition.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow definition not found or invalid: " + workflowId, exception);
        }
    }
}
