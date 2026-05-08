package com.involutionhell.backend.rag.infrastructure.web;

import com.involutionhell.backend.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * 针对 RAG 模块的专属全局异常拦截器。
 * 通过 basePackages 限定生效范围为 com.involutionhell.backend.rag 包下的控制器，
 * 并通过 @Order(Ordered.HIGHEST_PRECEDENCE) 抢占通用异常处理器的优先级。
 */
@RestControllerAdvice(basePackages = "com.involutionhell.backend.rag")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RagExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RagExceptionHandler.class);

    /**
     * 保留业务层显式声明的 HTTP 语义，例如 404 会话不存在、409 会话归属冲突。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(exception.getReason() == null ? status.getReasonPhrase() : exception.getReason()));
    }

    /**
     * 将参数校验异常转换为 400，避免被 RAG 兜底异常处理器包装成 500。
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(resolveValidationMessage(exception)));
    }

    /**
     * 处理 RAG 业务逻辑中主动抛出的校验异常
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(Exception exception) {
        log.warn("RAG 模块业务流程拦截: {}", exception.getMessage());
        // 可以根据需要加入 RAG 专属的业务状态码，目前直接使用 ApiResponse.fail
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("RAG 业务异常: " + exception.getMessage()));
    }

    /**
     * 兜底处理 RAG 模块其他未预期的运行时异常 (如网络中断、向量库连接失败等)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("RAG 模块发生未预期的系统异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("RAG 系统异常: " + exception.getMessage()));
    }

    private String resolveValidationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return Optional.ofNullable(methodArgumentNotValidException.getBindingResult().getFieldError())
                    .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                    .orElse("请求参数不合法");
        }
        if (exception instanceof BindException bindException) {
            return Optional.ofNullable(bindException.getBindingResult().getFieldError())
                    .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                    .orElse("请求参数不合法");
        }
        if (exception instanceof ConstraintViolationException constraintViolationException) {
            return constraintViolationException.getConstraintViolations().stream()
                    .findFirst()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .orElse("请求参数不合法");
        }
        return "请求参数不合法";
    }
}
