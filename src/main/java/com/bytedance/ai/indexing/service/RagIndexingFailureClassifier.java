package com.bytedance.ai.indexing.service;

import com.bytedance.ai.indexing.model.RagIndexFailure;
import com.bytedance.ai.indexing.workflow.IndexWorkflowTransitionException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * 索引失败分类器。
 *
 * <p>把底层异常统一归类为可重试/不可重试和稳定 reason，供工作流状态、结构化日志、
 * 指标和告警共同使用，避免配置错误、鉴权错误等永久失败被无限重试。
 */
@Component
public class RagIndexingFailureClassifier {

    private static final RagIndexFailure STALE_MESSAGE = new RagIndexFailure(false, "stale-message");
    private static final RagIndexFailure INTERRUPTED = new RagIndexFailure(false, "interrupted");
    private static final RagIndexFailure DATABASE_TRANSIENT = new RagIndexFailure(true, "database-transient");
    private static final RagIndexFailure DATABASE = new RagIndexFailure(false, "database");
    private static final RagIndexFailure TIMEOUT = new RagIndexFailure(true, "timeout");
    private static final RagIndexFailure NETWORK = new RagIndexFailure(true, "network");
    private static final RagIndexFailure INVALID_REQUEST = new RagIndexFailure(false, "invalid-request");
    private static final RagIndexFailure EMPTY_DOCUMENT = new RagIndexFailure(false, "empty-document");
    private static final RagIndexFailure AUTHENTICATION = new RagIndexFailure(false, "authentication");
    private static final RagIndexFailure NOT_FOUND = new RagIndexFailure(false, "not-found");
    private static final RagIndexFailure CONFIGURATION = new RagIndexFailure(false, "configuration");
    private static final RagIndexFailure UNKNOWN = new RagIndexFailure(true, "unknown");

    private static final List<CauseRule> CAUSE_RULES = List.of(
            new CauseRule(INTERRUPTED, InterruptedException.class),
            new CauseRule(DATABASE_TRANSIENT, RecoverableDataAccessException.class, TransientDataAccessException.class),
            new CauseRule(DATABASE, DataAccessException.class),
            new CauseRule(TIMEOUT, HttpTimeoutException.class, SocketTimeoutException.class, TimeoutException.class),
            new CauseRule(NETWORK, ConnectException.class, SocketException.class, IOException.class)
    );

    private static final List<MessageRule> MESSAGE_RULES = List.of(
            new MessageRule(EMPTY_DOCUMENT, "文档内容为空", "content is empty"),
            new MessageRule(
                    AUTHENTICATION,
                    "api key",
                    "authentication",
                    "unauthorized",
                    "forbidden",
                    "permission denied",
                    "鉴权",
                    "认证"
            ),
            new MessageRule(NOT_FOUND, "http 404", "404 - no response body available", "not found"),
            new MessageRule(
                    CONFIGURATION,
                    "must configure",
                    "invalid configuration",
                    "embedding dimension",
                    "vector dimension",
                    "schema",
                    "参数错误",
                    "配置错误"
            ),
            new MessageRule(TIMEOUT, "timeout", "deadline exceeded", "temporarily unavailable"),
            new MessageRule(NETWORK, "connection reset", "connection refused", "broken pipe", "network")
    );

    /**
     * 按异常类型链和消息关键词判定失败分类。
     *
     * @param throwable 索引链路抛出的异常
     * @return 失败分类结果
     */
    public RagIndexFailure classify(Throwable throwable) {
        if (isTerminalWorkflowGuardFailure(throwable)) {
            return STALE_MESSAGE;
        }
        for (CauseRule rule : CAUSE_RULES) {
            if (rule.matches(throwable)) {
                return rule.failure();
            }
        }
        if (throwable instanceof IllegalArgumentException) {
            return INVALID_REQUEST;
        }

        String message = messageOf(throwable);
        for (MessageRule rule : MESSAGE_RULES) {
            if (rule.matches(message)) {
                return rule.failure();
            }
        }
        return UNKNOWN;
    }

    private static boolean isTerminalWorkflowGuardFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IndexWorkflowTransitionException) {
                String message = current.getMessage();
                return message != null && message.contains("终态任务不允许继续推进");
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String messageOf(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage().toLowerCase(Locale.ROOT);
            }
            current = current.getCause();
        }
        return "";
    }

    private record CauseRule(RagIndexFailure failure, List<Class<? extends Throwable>> types) {

        @SafeVarargs
        CauseRule(RagIndexFailure failure, Class<? extends Throwable>... types) {
            this(failure, List.of(types));
        }

        boolean matches(Throwable throwable) {
            for (Class<? extends Throwable> type : types) {
                if (containsCause(throwable, type)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record MessageRule(RagIndexFailure failure, List<String> markers) {

        MessageRule(RagIndexFailure failure, String... markers) {
            this(failure, List.of(markers));
        }

        boolean matches(String message) {
            for (String marker : markers) {
                if (message.contains(marker)) {
                    return true;
                }
            }
            return false;
        }
    }
}
