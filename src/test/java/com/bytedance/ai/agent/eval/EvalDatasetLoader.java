package com.bytedance.ai.agent.eval;

import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code eval/*.json} 资源加载为 {@link EvalRunner.Dataset}。
 *
 * <p>独立成一个工具类是因为 EvalRunner 本身不应该耦合 Jackson 配置：
 * Spring 集成测试可以直接喂 dataset；脚本入口可以走这个 loader。
 */
public final class EvalDatasetLoader {

    private final RagJsonCodec jsonCodec;

    public EvalDatasetLoader(RagJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    @SuppressWarnings("unchecked")
    public EvalRunner.Dataset load(String classpath) throws Exception {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            Map<String, Object> raw = jsonCodec.readMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            String version = String.valueOf(raw.getOrDefault("datasetVersion", "unknown"));
            List<String> intents = raw.get("intents") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            List<Map<String, Object>> rawCases = (List<Map<String, Object>>) raw.getOrDefault("cases", List.of());
            List<EvalRunner.Case> cases = new ArrayList<>(rawCases.size());
            for (Map<String, Object> rc : rawCases) {
                cases.add(new EvalRunner.Case(
                        String.valueOf(rc.get("id")),
                        String.valueOf(rc.getOrDefault("category", "uncategorized")),
                        rc.get("intent") == null ? null : String.valueOf(rc.get("intent")),
                        String.valueOf(rc.getOrDefault("query", "")),
                        toStringList(rc.get("expectedSpuRefs")),
                        toStringList(rc.get("mustNotTags"))
                ));
            }
            return new EvalRunner.Dataset(version, intents, cases);
        }
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }
}
