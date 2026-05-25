package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.cart.api.CartQueryFacade;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

@Component
public class ListCartToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "list_cart";

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
                }
              },
              "required":["userId","conversationId"]
            }
            """;

    private final CartQueryFacade cartQueryFacade;
    private final RagJsonCodec jsonCodec;

    public ListCartToolCallback(
            CartQueryFacade cartQueryFacade,
            RagJsonCodec jsonCodec
    ) {
        this.cartQueryFacade = cartQueryFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("""
                        查看当前会话购物车。
                        这是只读工具，只返回购物车当前内容，不会修改购物车。
                        当用户询问购物车里有什么、查看购物车、确认已加购商品时调用。
                        userId 和 conversationId 必须来自系统上下文，不要由模型猜测。
                        """)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        CartToolInput input = jsonCodec.read(toolInput, CartToolInput.class);
        CartToolOutput output = list(input);
        return jsonCodec.write(output);
    }

    public CartToolOutput list(CartToolInput input) {
        validate(input);

        CartView cart = cartQueryFacade.getActiveCart(
                input.userId(),
                input.conversationId()
        );

        return new CartToolOutput(
                TOOL_NAME,
                cart,
                Map.of("action", "list")
        );
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    private void validate(CartToolInput input) {
        if (input == null) {
            throw new IllegalArgumentException("list_cart 输入不能为空");
        }

        if (!StringUtils.hasText(input.userId())) {
            throw new IllegalArgumentException("list_cart.userId 不能为空");
        }

        if (!StringUtils.hasText(input.conversationId())) {
            throw new IllegalArgumentException("list_cart.conversationId 不能为空");
        }
    }

    public record CartToolInput(
            String userId,
            String conversationId
    ) {
        public CartToolInput {
            userId = userId == null ? null : userId.trim();
            conversationId = conversationId == null ? null : conversationId.trim();
        }
    }

    public record CartToolOutput(
            String toolName,
            CartView cart,
            Map<String, Object> facetsApplied
    ) {
        public CartToolOutput {
            facetsApplied = facetsApplied == null ? Map.of() : Map.copyOf(facetsApplied);
        }
    }
}