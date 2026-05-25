package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckStockToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "check_stock";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "spuId":{
                  "type":"integer",
                  "description":"商品 SPU ID。只有当用户明确提到商品 ID 时填写，不要猜测"
                },
                "externalRef":{
                  "type":"string",
                  "description":"商品外部编号，例如 SPU-10001。只有当用户明确提到时填写，不要猜测"
                },
                "lastTurnSpuRefs":{
                  "type":"array",
                  "items":{"type":"string"},
                  "description":"上一轮工具返回的商品 externalRef 列表，用于用户说'这个/刚才那个/第一个'时兜底"
                }
              },
              "anyOf":[
                {"required":["spuId"]},
                {"required":["externalRef"]},
                {"required":["lastTurnSpuRefs"]}
              ]
            }
            """;

    private final CatalogQueryFacade catalogQueryFacade;
    private final RagJsonCodec jsonCodec;

    public CheckStockToolCallback(CatalogQueryFacade catalogQueryFacade, RagJsonCodec jsonCodec) {
        this.catalogQueryFacade = catalogQueryFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("查询商品 SPU 和 SKU 库存")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        return jsonCodec.write(check(jsonCodec.read(toolInput, CheckStockInput.class)));
    }

    public CheckStockOutput check(CheckStockInput input) {
        CatalogSpuView spu = resolveSpu(input);

        List<SkuStock> skuStocks = spu.skus() == null
                ? List.of()
                : spu.skus().stream().map(this::toSkuStock).toList();

        return new CheckStockOutput(
                TOOL_NAME,
                spu.id(),
                spu.externalRef(),
                spu.title(),
                spu.stock(),
                available(spu),
                skuStocks
        );
    }

    private boolean available(CatalogSpuView spu) {
        if (spu.stock() != null && spu.stock() > 0) {
            return true;
        }

        if (spu.skus() == null || spu.skus().isEmpty()) {
            return false;
        }

        return spu.skus().stream()
                .anyMatch(sku -> sku.stock() != null && sku.stock() > 0);
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP, IntentType.REFINE, IntentType.FILTER_BY_ATTR);
    }

    private CatalogSpuView resolveSpu(@NonNull CheckStockInput input) {
        String externalRef = resolveExternalRef(input);
        if (StringUtils.hasText(externalRef)) {
            Optional<CatalogSpuView> spu = catalogQueryFacade.findSpuByExternalRef(externalRef);
            if (spu.isPresent()) {
                return spu.get();
            }
        }
        throw new IllegalArgumentException("无法解析要查询库存的商品");
    }

    private String resolveExternalRef(@NonNull CheckStockInput input) {
        if (StringUtils.hasText(input.externalRef())) {
            return input.externalRef().trim();
        }
        if (input.lastTurnSpuRefs() != null && !input.lastTurnSpuRefs().isEmpty()) {
            return input.lastTurnSpuRefs().getFirst();
        }
        return null;
    }

    private SkuStock toSkuStock(CatalogSkuView sku) {
        return new SkuStock(sku.id(), sku.skuCode(), sku.specJson(), sku.stock(), sku.status());
    }

    public record CheckStockInput(Long spuId, String externalRef, List<String> lastTurnSpuRefs) {
        public CheckStockInput {
            lastTurnSpuRefs = lastTurnSpuRefs == null ? List.of() : List.copyOf(lastTurnSpuRefs);
        }
    }

    public record CheckStockOutput(
            String toolName,
            Long spuId,
            String externalRef,
            String title,
            Integer stock,
            boolean available,
            List<SkuStock> skus
    ) {
        public CheckStockOutput {
            skus = skus == null ? List.of() : List.copyOf(skus);
        }
    }

    public record SkuStock(Long skuId, String skuCode, Map<String, Object> specJson, Integer stock, String status) {
    }
}
