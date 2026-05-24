package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.order.api.OrderCommandFacade;
import com.bytedance.ai.order.api.PlaceOrderResult;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

@Component
public class PlaceOrderToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "place_order";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "userId":{"type":"string"},
                "conversationId":{"type":"string"},
                "address":{"type":"object"},
                "confirmPriceChange":{"type":"boolean"}
              },
              "required":["userId","conversationId"]
            }
            """;

    private final OrderCommandFacade orderCommandFacade;
    private final RagJsonCodec jsonCodec;

    public PlaceOrderToolCallback(OrderCommandFacade orderCommandFacade, RagJsonCodec jsonCodec) {
        this.orderCommandFacade = orderCommandFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("将当前购物车提交为订单；若价格变化则先要求用户二次确认")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return jsonCodec.write(place(jsonCodec.read(toolInput, PlaceOrderInput.class)));
    }

    public PlaceOrderOutput place(PlaceOrderInput input) {
        PlaceOrderResult result = orderCommandFacade.placeOrder(
                input.userId(),
                input.conversationId(),
                input.address(),
                input.confirmPriceChange()
        );
        return new PlaceOrderOutput(TOOL_NAME, result, Map.of("action", "place_order", "code", result.code()));
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    public record PlaceOrderInput(
            String userId,
            String conversationId,
            Map<String, Object> address,
            boolean confirmPriceChange
    ) {
        public PlaceOrderInput {
            address = address == null ? Map.of() : Map.copyOf(address);
        }
    }

    public record PlaceOrderOutput(String toolName, PlaceOrderResult result, Map<String, Object> facetsApplied) {
    }
}
