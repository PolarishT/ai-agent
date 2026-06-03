package com.bytedance.ai.graph.planning;

import com.bytedance.ai.shared.support.RagJsonCodec;
import com.bytedance.ai.shared.support.RagLogFields;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务规划 LLM 服务（PLANNING 阶段）：把复合目标拆成有序任务清单。
 *
 * <p>复用 intentChatClient（temperature 0）。LLM 失败 / 空结果时抛 {@link TaskPlanningException}，
 * 调用方走「单任务兜底」。
 */
@Service
public class TaskPlanningService {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanningService.class);

    private static final String P_GOAL = "{{goalText}}";
    private static final String P_INTENT = "{{intent}}";
    private static final String P_RECENT = "{{recentContext}}";
    private static final int MAX_TASKS = 8;

    private final ChatClient chatClient;
    private final RagJsonCodec jsonCodec;
    private final String templatePath;

    private String template;

    public TaskPlanningService(
            @Qualifier("intentChatClient") ChatClient chatClient,
            RagJsonCodec jsonCodec,
            @Value("${graph.agent.planning-llm.prompt-template-path:prompts/task-planning-v1.txt}")
            String templatePath
    ) {
        this.chatClient = chatClient;
        this.jsonCodec = jsonCodec;
        this.templatePath = templatePath;
    }

    @PostConstruct
    public void init() {
        this.template = loadTemplate(templatePath);
    }

    /**
     * 拆解目标为有序任务清单。
     *
     * @return 至少 1 个 {@link PlannedTask}；LLM 失败抛 {@link TaskPlanningException}
     */
    public List<PlannedTask> plan(String goalText, String intent, String recentContext) {
        String prompt = template
                .replace(P_GOAL, safe(goalText))
                .replace(P_INTENT, safe(intent))
                .replace(P_RECENT, safe(recentContext));

        PlanningResult result;
        try {
            result = chatClient.prompt(prompt).call().entity(PlanningResult.class);
        } catch (Exception ex) {
            log.atWarn()
                    .addKeyValue(RagLogFields.EVENT_NAME, "task_planning.call_failed")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_FAILURE)
                    .addKeyValue("planning.intent", intent)
                    .log("task planning LLM call failed: {}", ex.toString());
            throw new TaskPlanningException("task planning LLM call failed", ex);
        }
        if (result == null || result.tasks() == null || result.tasks().isEmpty()) {
            throw new TaskPlanningException("task planning returned empty plan");
        }
        List<PlannedTask> tasks = new ArrayList<>();
        for (PlannedTask t : result.tasks()) {
            if (t == null || !StringUtils.hasText(t.taskName())) {
                continue;
            }
            tasks.add(t);
            if (tasks.size() >= MAX_TASKS) {
                break;
            }
        }
        if (tasks.isEmpty()) {
            throw new TaskPlanningException("task planning produced no usable task");
        }
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "task_planning.planned")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue("planning.intent", intent)
                .addKeyValue("planning.task_count", tasks.size())
                .log("task planning produced {} tasks for intent {}", tasks.size(), intent);
        return tasks;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new IllegalStateException("Task planning prompt template not found: " + path);
            }
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load task planning prompt template: " + path, ex);
        }
    }

    /** LLM 返回的单个计划任务。 */
    public record PlannedTask(String taskName, String taskType, String workflow) {
    }

    /** LLM 返回的整体结构。 */
    public record PlanningResult(List<PlannedTask> tasks) {
    }

    /** 规划失败标记异常，调用方据此走单任务兜底。 */
    public static class TaskPlanningException extends RuntimeException {
        public TaskPlanningException(String message) {
            super(message);
        }

        public TaskPlanningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
