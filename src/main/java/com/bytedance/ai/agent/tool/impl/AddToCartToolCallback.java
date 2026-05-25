package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.cart.api.CartCommandFacade;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import lombok.NonNull;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AddToCartToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "add_to_cart";

    private static final int DEFAULT_QUANTITY = 1;

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
                "spuId":{
                  "type":"integer",
                  "description":"商品 SPU ID。只有当用户明确选择了某个商品或上下文已确定商品时填写"
                },
                "externalRef":{
                  "type":"string",
                  "description":"商品外部编号，例如 SPU-10001。只有当用户明确选择了某个商品或上下文已确定商品时填写"
                },
                "lastTurnSpuRefs":{
                  "type":"array",
                  "items":{"type":"string"},
                  "minItems":1,
                  "description":"上一轮工具返回的商品 externalRef 列表。用户说'刚才那个'、'第一个'、'这款'时可用于解析商品"
                },
                "quantity":{
                  "type":"integer",
                  "minimum":1,
                  "description":"加入购物车数量。用户未说明时填 1"
                },
                "expectedUnitPrice":{
                  "type":"number",
                  "minimum":0,
                  "description":"用户看到或确认的预期单价，用于价格变动校验；不确定则不填"
                }
              },
              "required":["userId","conversationId"],
              "anyOf":[
                {"required":["spuId"]},
                {"required":["externalRef"]},
                {"required":["lastTurnSpuRefs"]}
              ]
            }
            """;

    private final CartCommandFacade cartCommandFacade;
    private final RagJsonCodec jsonCodec;

    public AddToCartToolCallback(
            CartCommandFacade cartCommandFacade,
            RagJsonCodec jsonCodec
    ) {
        this.cartCommandFacade = cartCommandFacade;
        this.jsonCodec = jsonCodec;
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

    @Override
    public @org.jspecify.annotations.NonNull ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("""
                        将用户已明确确认的商品加入购物车。
                        仅当用户明确表达加入购物车、购买、下单前加入等意图时调用。
                        不要替用户猜测商品、数量或价格；商品事实必须来自系统上下文或商品工具返回结果。
                        支持根据上一轮商品引用解析“刚才那款”“这个”“第一个”等代词。
                        """)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        CartToolInput input = jsonCodec.read(toolInput, CartToolInput.class);
        CartToolOutput output = add(input);
        return jsonCodec.write(output);
    }

    public CartToolOutput add(CartToolInput input) {
        validate(input);

        String externalRef = resolveExternalRef(input);
        int quantity = resolveQuantity(input.quantity());

        CartView cart = cartCommandFacade.addItem(
                input.userId(),
                input.conversationId(),
                input.spuId(),
                externalRef,
                quantity,
                input.expectedUnitPrice()
        );

        return new CartToolOutput(
                TOOL_NAME,
                cart,
                buildFacetsApplied(input, externalRef, quantity)
        );
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    private void validate(CartToolInput input) {
        if (input == null) {
            throw new IllegalArgumentException("add_to_cart 输入不能为空");
        }

        if (!StringUtils.hasText(input.userId())) {
            throw new IllegalArgumentException("add_to_cart.userId 不能为空");
        }

        if (!StringUtils.hasText(input.conversationId())) {
            throw new IllegalArgumentException("add_to_cart.conversationId 不能为空");
        }

        if (input.spuId() == null && !StringUtils.hasText(resolveExternalRef(input))) {
            throw new IllegalArgumentException("无法解析要加入购物车的商品");
        }

        if (input.quantity() != null && input.quantity() < 1) {
            throw new IllegalArgumentException("add_to_cart.quantity 必须大于等于 1");
        }

        if (input.expectedUnitPrice() != null && input.expectedUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("add_to_cart.expectedUnitPrice 不能为负数");
        }
    }

    private int resolveQuantity(Integer quantity) {
        return quantity == null ? DEFAULT_QUANTITY : quantity;
    }

    private Map<String, Object> buildFacetsApplied(
            CartToolInput input,
            String resolvedExternalRef,
            int resolvedQuantity
    ) {
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("action", "add");
        facets.put("resolvedSpuId", input.spuId());
        facets.put("resolvedExternalRef", nullToEmpty(resolvedExternalRef));
        facets.put("quantity", resolvedQuantity);

        if (input.expectedUnitPrice() != null) {
            facets.put("expectedUnitPrice", input.expectedUnitPrice());
        }

        return facets;
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
            userId = userId == null ? null : userId.trim();
            conversationId = conversationId == null ? null : conversationId.trim();
            externalRef = externalRef == null ? null : externalRef.trim();

            lastTurnSpuRefs = lastTurnSpuRefs == null
                    ? List.of()
                    : lastTurnSpuRefs.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
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