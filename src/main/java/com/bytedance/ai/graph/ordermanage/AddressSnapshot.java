package com.bytedance.ai.graph.ordermanage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下单地址快照，固定一次订单确认时使用的收货人和地址信息。
 */
public record AddressSnapshot(
        String receiverName,
        String phone,
        String addressText,
        String postcode,
        String city,
        String state
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("receiverName", receiverName);
        map.put("phone", phone);
        map.put("addressText", addressText);
        map.put("detail", addressText);
        if (postcode != null) {
            map.put("postcode", postcode);
            map.put("postalCode", postcode);
        }
        if (city != null) {
            map.put("city", city);
        }
        if (state != null) {
            map.put("state", state);
        }
        return map;
    }

    public static AddressSnapshot fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new AddressSnapshot(null, null, null, null, null, null);
        }
        return new AddressSnapshot(
                text(map, "receiverName"),
                text(map, "phone"),
                firstText(map, "addressText", "detail"),
                firstText(map, "postcode", "postalCode"),
                text(map, "city"),
                text(map, "state")
        );
    }

    private static String firstText(Map<String, Object> map, String first, String second) {
        String value = text(map, first);
        return value == null ? text(map, second) : value;
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}
