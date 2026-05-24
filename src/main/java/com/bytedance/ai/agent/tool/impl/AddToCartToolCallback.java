package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.cart.api.CartCommandFacade;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AddToCartToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "add_to_cart";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "userId":{"type":"string"},
                "conversationId":{"type":"string"},
                "spuId":{"type":"integer"},
                "externalRef":{"type":"string"},
                "lastTurnSpuRefs":{"type":"array","items":{"type":"string"}},
                "quantity":{"type":"integer","minimum":1},
                "expectedUnitPrice":{"type":"number"}
              },
              "required":["userId","conversationId"]
            }
            """;

    private final CartCommandFacade cartCommandFacade;
    private final RagJsonCodec jsonCodec;

    public AddToCartToolCallback(CartCommandFacade cartCommandFacade, RagJsonCodec jsonCodec) {
        this.cartCommandFacade = cartCommandFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("把用户确认的商品加入购物车，支持“刚才那款”代词解析")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return jsonCodec.write(add(jsonCodec.read(toolInput, CartToolInput.class)));
    }

    public CartToolOutput add(CartToolInput input) {
        String externalRef = resolveExternalRef(input);
        CartView cart = cartCommandFacade.addItem(
                input.userId(),
                input.conversationId(),
                input.spuId(),
                externalRef,
                input.quantity(),
                input.expectedUnitPrice()
        );
        return new CartToolOutput(TOOL_NAME, cart, Map.of("action", "add", "resolvedExternalRef", nullToEmpty(externalRef)));
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    static String resolveExternalRef(CartToolInput input) {
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record CartToolInput(
            String userId,
            String conversationId,
            Long spuId,
            String externalRef,
            List<String> lastTurnSpuRefs,
            Integer quantity,
            BigDecimal expectedUnitPrice
    ) {
        public CartToolInput {
            lastTurnSpuRefs = lastTurnSpuRefs == null ? List.of() : List.copyOf(lastTurnSpuRefs);
        }
    }

    public record CartToolOutput(String toolName, CartView cart, Map<String, Object> facetsApplied) {
    }
}
