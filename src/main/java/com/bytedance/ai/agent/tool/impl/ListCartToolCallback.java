package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.cart.api.CartQueryFacade;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

@Component
public class ListCartToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "list_cart";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "userId":{"type":"string"},
                "conversationId":{"type":"string"}
              },
              "required":["userId","conversationId"]
            }
            """;

    private final CartQueryFacade cartQueryFacade;
    private final RagJsonCodec jsonCodec;

    public ListCartToolCallback(CartQueryFacade cartQueryFacade, RagJsonCodec jsonCodec) {
        this.cartQueryFacade = cartQueryFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("查看当前会话购物车")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return jsonCodec.write(list(jsonCodec.read(toolInput, CartToolInput.class)));
    }

    public CartToolOutput list(CartToolInput input) {
        CartView cart = cartQueryFacade.getActiveCart(input.userId(), input.conversationId());
        return new CartToolOutput(TOOL_NAME, cart, Map.of("action", "list"));
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    public record CartToolInput(String userId, String conversationId) {
    }

    public record CartToolOutput(String toolName, CartView cart, Map<String, Object> facetsApplied) {
    }
}
