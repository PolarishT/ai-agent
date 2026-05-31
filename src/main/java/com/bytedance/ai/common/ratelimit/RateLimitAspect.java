package com.bytedance.ai.common.ratelimit;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 拦截 {@link RateLimit} 标注的方法，在执行前做多时间窗限流校验。
 *
 * <p>限流键 = 方法全限定名（命名空间，隔离不同接口） + SpEL 求值结果。
 * SpEL 可引用方法入参名（如 {@code #userId}），由 {@link MethodBasedEvaluationContext} 提供。
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final RateLimiterStore store;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    public RateLimitAspect(RateLimiterStore store) {
        this.store = store;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String key = resolveKey(joinPoint, method, rateLimit.key());
        store.acquireOrThrow(key, toRules(rateLimit.windows()), rateLimit.message());
        return joinPoint.proceed();
    }

    private List<RateRule> toRules(RateWindow[] windows) {
        return Arrays.stream(windows)
                .map(window -> new RateRule(window.seconds(), window.permits()))
                .toList();
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, Method method, String keyExpression) {
        String namespace = method.getDeclaringClass().getName() + "#" + method.getName();
        if (!StringUtils.hasText(keyExpression)) {
            return namespace; // 无 key：方法级全局限流
        }
        try {
            Expression expression = expressionCache.computeIfAbsent(keyExpression, expressionParser::parseExpression);
            EvaluationContext context = new MethodBasedEvaluationContext(
                    joinPoint.getTarget(), method, joinPoint.getArgs(), parameterNameDiscoverer);
            Object value = expression.getValue(context);
            return namespace + "::" + (value == null ? "null" : value);
        } catch (RuntimeException exception) {
            // SpEL 解析/求值失败时退化为方法级全局限流，记录但不阻断请求
            log.warn("RateLimit SpEL 解析失败，退化为方法级限流: expr={}, method={}, error={}",
                    keyExpression, namespace, exception.getMessage());
            return namespace;
        }
    }
}
