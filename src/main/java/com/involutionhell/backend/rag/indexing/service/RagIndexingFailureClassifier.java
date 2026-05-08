package com.involutionhell.backend.rag.indexing.service;

import com.involutionhell.backend.rag.indexing.model.RagIndexFailure;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTransitionException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * 将索引异常归类为可重试或不可重试，避免把配置错误反复重试。
 */
@Component
public class RagIndexingFailureClassifier {

    public RagIndexFailure classify(Throwable throwable) {
        if (isTerminalWorkflowGuardFailure(throwable)) {
            return new RagIndexFailure(false, "stale-message");
        }
        if (containsCause(throwable, InterruptedException.class)) {
            return new RagIndexFailure(false, "interrupted");
        }
        if (containsCause(throwable, RecoverableDataAccessException.class)
                || containsCause(throwable, TransientDataAccessException.class)) {
            return new RagIndexFailure(true, "database-transient");
        }
        if (containsCause(throwable, DataAccessException.class)) {
            return new RagIndexFailure(false, "database");
        }
        if (containsCause(throwable, HttpTimeoutException.class)
                || containsCause(throwable, SocketTimeoutException.class)
                || containsCause(throwable, TimeoutException.class)) {
            return new RagIndexFailure(true, "timeout");
        }
        if (containsCause(throwable, ConnectException.class)
                || containsCause(throwable, SocketException.class)
                || containsCause(throwable, IOException.class)) {
            return new RagIndexFailure(true, "network");
        }
        if (throwable instanceof IllegalArgumentException) {
            return new RagIndexFailure(false, "invalid-request");
        }

        String message = messageOf(throwable);
        if (message.contains("文档内容为空") || message.contains("content is empty")) {
            return new RagIndexFailure(false, "empty-document");
        }
        if (message.contains("api key") || message.contains("authentication") || message.contains("unauthorized")
                || message.contains("forbidden") || message.contains("permission denied")
                || message.contains("鉴权") || message.contains("认证")) {
            return new RagIndexFailure(false, "authentication");
        }
        if (message.contains("http 404") || message.contains("404 - no response body available")
                || message.contains("not found")) {
            return new RagIndexFailure(false, "not-found");
        }
        if (message.contains("must configure") || message.contains("invalid configuration")
                || message.contains("embedding dimension") || message.contains("vector dimension")
                || message.contains("schema") || message.contains("参数错误") || message.contains("配置错误")) {
            return new RagIndexFailure(false, "configuration");
        }
        if (message.contains("timeout") || message.contains("deadline exceeded")
                || message.contains("temporarily unavailable")) {
            return new RagIndexFailure(true, "timeout");
        }
        if (message.contains("connection reset") || message.contains("connection refused")
                || message.contains("broken pipe") || message.contains("network")) {
            return new RagIndexFailure(true, "network");
        }
        return new RagIndexFailure(true, "unknown");
    }

    private boolean isTerminalWorkflowGuardFailure(Throwable throwable) {
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

    private boolean containsCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String messageOf(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage().toLowerCase(Locale.ROOT);
            }
            current = current.getCause();
        }
        return "";
    }
}
