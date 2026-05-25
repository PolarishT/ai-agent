package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
import com.bytedance.ai.shared.support.RagJsonCodec;
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
                "spuId":{"type":"integer"},
                "externalRef":{"type":"string"},
                "lastTurnSpuRefs":{"type":"array","items":{"type":"string"}}
              }
            }
            """;

    private final CatalogQueryFacade catalogQueryFacade;
    private final RagJsonCodec jsonCodec;

    public CheckStockToolCallback(CatalogQueryFacade catalogQueryFacade, RagJsonCodec jsonCodec) {
        this.catalogQueryFacade = catalogQueryFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("查询商品 SPU 和 SKU 库存")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return jsonCodec.write(check(jsonCodec.read(toolInput, CheckStockInput.class)));
    }

    public CheckStockOutput check(CheckStockInput input) {
        CatalogSpuView spu = resolveSpu(input);
        return new CheckStockOutput(
                TOOL_NAME,
                spu.id(),
                spu.externalRef(),
                spu.title(),
                spu.stock(),
                spu.stock() != null && spu.stock() > 0,
                spu.skus().stream().map(this::toSkuStock).toList()
        );
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP, IntentType.REFINE, IntentType.FILTER_BY_ATTR);
    }

    private CatalogSpuView resolveSpu(CheckStockInput input) {
        if (input != null && input.spuId() != null) {
            return catalogQueryFacade.getSpu(input.spuId());
        }
        String externalRef = resolveExternalRef(input);
        if (StringUtils.hasText(externalRef)) {
            Optional<CatalogSpuView> spu = catalogQueryFacade.findSpuByExternalRef(externalRef);
            if (spu.isPresent()) {
                return spu.get();
            }
        }
        throw new IllegalArgumentException("无法解析要查询库存的商品");
    }

    private String resolveExternalRef(CheckStockInput input) {
        if (input == null) {
            return null;
        }
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
