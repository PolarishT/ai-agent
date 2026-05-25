package com.bytedance.ai.agent.workflow;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves workflow mapping expressions like {@code $.state.cards}, {@code $.request.userId},
 * {@code $.pending.selection.selectedExternalRef}. Returns the literal value when the expression is not a reference.
 *
 * <p>Knows nothing about specific business fields — every leaf access goes through bean-style accessor reflection
 * or {@link Map} lookup, so the only thing the executor cares about is "is this a `$.` reference, and if so, walk it".
 */
@Component
public class WorkflowExpressionResolver {

    public Object resolve(
            Object expression,
            WorkflowRuntimeState state,
            EcommerceWorkflowEngine.WorkflowRequest request
    ) {
        if (expression instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolve(v, state, request)));
            return resolved;
        }
        if (expression instanceof List<?> list) {
            return list.stream().map(item -> resolve(item, state, request)).toList();
        }
        if (!(expression instanceof String text) || !text.startsWith("$.")) {
            return expression;
        }
        String[] segments = text.substring(2).split("\\.");
        if (segments.length == 0) {
            return null;
        }
        Object root = switch (segments[0]) {
            case "state" -> state;
            case "request" -> request == null ? null : request.request();
            case "pending" -> state == null ? null : pendingRoot(state);
            default -> null;
        };
        if (root == null) {
            return null;
        }
        Object current = root;
        for (int i = 1; i < segments.length; i++) {
            current = readMember(current, segments[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object pendingRoot(WorkflowRuntimeState state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("selection", state.pendingSelection());
        root.put("confirmation", state.pendingConfirmation());
        root.put("slot", state.pendingSlot());
        return root;
    }

    private Object readMember(Object target, String member) {
        if (target == null || !StringUtils.hasText(member)) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(member);
        }
        return invokeAccessor(target, member);
    }

    private Object invokeAccessor(Object target, String member) {
        Class<?> type = target.getClass();
        String capitalized = Character.toUpperCase(member.charAt(0)) + member.substring(1);
        for (String candidate : List.of(member, "get" + capitalized, "is" + capitalized)) {
            try {
                Method method = type.getMethod(candidate);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("workflow expression access failed: " + member, exception);
            }
        }
        return null;
    }
}
