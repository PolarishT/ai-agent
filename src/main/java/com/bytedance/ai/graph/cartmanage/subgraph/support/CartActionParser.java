package com.bytedance.ai.graph.cartmanage.subgraph.support;

import com.bytedance.ai.graph.cartmanage.subgraph.CartAction;
import org.springframework.util.StringUtils;

import java.util.Locale;

public final class CartActionParser {

    private CartActionParser() {
    }

    public static CartAction safeCartAction(String value) {
        if (!StringUtils.hasText(value)) {
            return CartAction.UNKNOWN;
        }
        try {
            return CartAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return CartAction.UNKNOWN;
        }
    }

    public static CartAction parseCartAction(String value) {
        if (value == null) return CartAction.UNKNOWN;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ADD", "ADD_TO_CART" -> CartAction.ADD;
            case "REMOVE", "REMOVE_ITEM", "REMOVE_FROM_CART" -> CartAction.REMOVE;
            case "UPDATE_QUANTITY", "UPDATE", "UPDATE_CART_ITEM" -> CartAction.UPDATE_QUANTITY;
            case "VIEW", "VIEW_CART" -> CartAction.VIEW;
            case "CLEAR", "CLEAR_CART" -> CartAction.CLEAR;
            case "CONFIRM" -> CartAction.CONFIRM;
            case "CANCEL" -> CartAction.CANCEL;
            default -> CartAction.UNKNOWN;
        };
    }

    public static CartAction inferActionFromMessage(String message) {
        if (message == null) {
            return CartAction.UNKNOWN;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("加") || lower.contains("买") || lower.contains("加入")) return CartAction.ADD;
        if (lower.contains("删") || lower.contains("移除")) return CartAction.REMOVE;
        if (lower.contains("改") || lower.contains("更新") || lower.contains("数量")) return CartAction.UPDATE_QUANTITY;
        if (lower.contains("看") || lower.contains("查看") || lower.contains("我的购物车")) return CartAction.VIEW;
        if (lower.contains("清空") || lower.contains("清空购物车") || lower.contains("清掉购物车")) return CartAction.CLEAR;
        return CartAction.UNKNOWN;
    }

    public static boolean looksLikeNewCartRequest(String message) {
        CartAction action = inferActionFromMessage(message);
        return action == CartAction.ADD
                || action == CartAction.REMOVE
                || action == CartAction.UPDATE_QUANTITY
                || action == CartAction.VIEW
                || action == CartAction.CLEAR;
    }
}
