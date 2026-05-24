package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.cart.api.CartCommandFacade;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

@Component
public class RemoveFromCartToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "remove_from_cart";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "userId":{"type":"string"},
                "conversationId":{"type":"string"},
                "itemId":{"type":"integer"},
                "spuId":{"type":"integer"},
                "externalRef":{"type":"string"},
                "lastTurnSpuRefs":{"type":"array","items":{"type":"string"}}
              },
              "required":["userId","conversationId"]
            }
            """;

    private final CartCommandFacade cartCommandFacade;
    private final RagJsonCodec jsonCodec;

    public RemoveFromCartToolCallback(CartCommandFacade cartCommandFacade, RagJsonCodec jsonCodec) {
        this.cartCommandFacade = cartCommandFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("从购物车移除商品")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return jsonCodec.write(remove(jsonCodec.read(toolInput, CartToolInput.class)));
    }

    public CartToolOutput remove(CartToolInput input) {
        String externalRef = AddToCartToolCallback.resolveExternalRef(new AddToCartToolCallback.CartToolInput(
                input.userId(),
                input.conversationId(),
                input.spuId(),
                input.externalRef(),
                input.lastTurnSpuRefs(),
                null,
                null
        ));
        CartView cart = cartCommandFacade.removeItem(
                input.userId(),
                input.conversationId(),
                input.itemId(),
                input.spuId(),
                externalRef
        );
        return new CartToolOutput(TOOL_NAME, cart, Map.of("action", "remove", "resolvedExternalRef", externalRef == null ? "" : externalRef));
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    public record CartToolInput(
            String userId,
            String conversationId,
            Long itemId,
            Long spuId,
            String externalRef,
            List<String> lastTurnSpuRefs
    ) {
        public CartToolInput {
            lastTurnSpuRefs = lastTurnSpuRefs == null ? List.of() : List.copyOf(lastTurnSpuRefs);
        }
    }

    public record CartToolOutput(String toolName, CartView cart, Map<String, Object> facetsApplied) {
    }
}
