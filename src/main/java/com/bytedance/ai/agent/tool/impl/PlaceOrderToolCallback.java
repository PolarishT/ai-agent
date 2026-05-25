package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.order.api.OrderCommandFacade;
import com.bytedance.ai.order.api.PlaceOrderResult;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class PlaceOrderToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "place_order";

    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "userId":{
                  "type":"string",
                  "description":"当前用户 ID，必须来自系统上下文，不要由模型猜测"
                },
                "conversationId":{
                  "type":"string",
                  "description":"当前会话 ID，必须来自系统上下文，不要由模型猜测"
                },
                "address":{
                  "type":"object",
                  "description":"用户确认使用的收货地址。若系统已有默认地址且用户未修改，可不填或传空对象；不要由模型编造地址"
                },
                "confirmPriceChange":{
                  "type":"boolean",
                  "description":"当系统提示价格发生变化后，用户是否明确接受价格变化。未发生价格变化或用户未确认时填 false"
                },
                "userConfirmedOrder":{
                  "type":"boolean",
                  "description":"用户是否已经明确确认提交订单。只有用户明确说确认下单、提交订单、就买这些时才填 true"
                }
              },
              "required":["userId","conversationId","userConfirmedOrder"]
            }
            """;

    private final OrderCommandFacade orderCommandFacade;
    private final RagJsonCodec jsonCodec;

    public PlaceOrderToolCallback(
            OrderCommandFacade orderCommandFacade,
            RagJsonCodec jsonCodec
    ) {
        this.orderCommandFacade = orderCommandFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("""
                        将当前会话购物车提交为订单。
                        这是高风险写操作，只能在用户明确确认下单后调用。
                        不要替用户猜测 userId、conversationId、地址或价格确认状态。
                        如果价格变化，需要先让用户确认是否接受价格变化，再调用本工具。
                        如果用户只是查看购物车、询问价格、询问库存，不要调用本工具。
                        """)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        PlaceOrderInput input = jsonCodec.read(toolInput, PlaceOrderInput.class);
        PlaceOrderOutput output = place(input);
        return jsonCodec.write(output);
    }

    public PlaceOrderOutput place(PlaceOrderInput input) {
        validate(input);

        PlaceOrderResult result = orderCommandFacade.placeOrder(
                input.userId(),
                input.conversationId(),
                input.address(),
                input.confirmPriceChange()
        );

        return new PlaceOrderOutput(
                TOOL_NAME,
                result,
                buildFacetsApplied(input, result)
        );
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    private void validate(PlaceOrderInput input) {
        if (input == null) {
            throw new IllegalArgumentException("place_order 输入不能为空");
        }

        if (!StringUtils.hasText(input.userId())) {
            throw new IllegalArgumentException("place_order.userId 不能为空");
        }

        if (!StringUtils.hasText(input.conversationId())) {
            throw new IllegalArgumentException("place_order.conversationId 不能为空");
        }

        if (!input.userConfirmedOrder()) {
            throw new IllegalArgumentException("用户尚未明确确认下单，不能提交订单");
        }
    }

    private Map<String, Object> buildFacetsApplied(
            PlaceOrderInput input,
            PlaceOrderResult result
    ) {
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("action", "place_order");
        facets.put("userConfirmedOrder", input.userConfirmedOrder());
        facets.put("confirmPriceChange", input.confirmPriceChange());
        facets.put("addressProvided", input.address() != null && !input.address().isEmpty());

        if (result != null) {
            facets.put("code", result.code());
        }

        return facets;
    }

    public record PlaceOrderInput(
            String userId,
            String conversationId,
            Map<String, Object> address,
            boolean confirmPriceChange,
            boolean userConfirmedOrder
    ) {
        public PlaceOrderInput {
            userId = userId == null ? null : userId.trim();
            conversationId = conversationId == null ? null : conversationId.trim();
            address = address == null ? Map.of() : Map.copyOf(address);
        }
    }

    public record PlaceOrderOutput(
            String toolName,
            PlaceOrderResult result,
            Map<String, Object> facetsApplied
    ) {
        public PlaceOrderOutput {
            facetsApplied = facetsApplied == null ? Map.of() : Map.copyOf(facetsApplied);
        }
    }
}