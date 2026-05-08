package com.involutionhell.backend.rag.shared.support;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一承接 RAG 模块内的 JSON 编解码，避免业务层直接散落 jsonMapper 调用。
 */
@Component
public class RagJsonCodec {

    private final JavaType MAP_TYPE;

    private final JsonMapper jsonMapper;

    public RagJsonCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.MAP_TYPE = jsonMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class);
    }

    public String write(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    public <T> T read(byte[] bytes, Class<T> type) {
        try {
            // 直接传入 byte[] 比 new String(bytes) 性能更好
            // 因为减少了将字节数组转换为字符串时的字符集编解码开销和内存分配
            return jsonMapper.readValue(bytes, type);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return jsonMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }

    public <T> T read(String json, JavaType type) {
        try {
            return jsonMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }

    public Map<String, Object> readMap(String json) {
        Map<String, Object> map = read(json, MAP_TYPE);
        // [修正 2]: 保证返回值可变性的一致性。
        // 因为 MAP_TYPE 指定了 LinkedHashMap，read() 出来的本身就是 LinkedHashMap，直接返回即可。
        // 如果为 null，返回一个空的 LinkedHashMap，避免抛出 UnsupportedOperationException。
        return map == null ? new LinkedHashMap<>() : map;
    }

    public Map<String, Object> convertToMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }

        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }

        try {
            return jsonMapper.convertValue(value, MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("对象转 Map 失败", exception);
        }
    }
}