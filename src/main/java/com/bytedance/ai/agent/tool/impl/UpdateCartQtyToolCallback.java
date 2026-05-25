package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.cart.api.CartCommandFacade;
import com.bytedance.ai.cart.api.CartView;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class UpdateCartQtyToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "update_cart_qty";

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
                "itemId":{
                  "type":"integer",
                  "minimum":1,
                  "description":"购物车条目 ID。优先使用 itemId 精确修改购物车条目数量"
                },
                "spuId":{
                  "type":"integer",
                  "minimum":1,
                  "description":"商品 SPU ID。只有当上下文已明确商品时填写，不要猜测"
                },
                "externalRef":{
                  "type":"string",
                  "description":"商品外部编号，例如 SPU-10001。只有当上下文已明确商品时填写，不要猜测"
                },
                "lastTurnSpuRefs":{
                  "type":"array",
                  "items":{"type":"string"},
                  "minItems":1,
                  "description":"上一轮工具返回的商品 externalRef 列表。用户说'刚才那个'、'这个'、'第一个'时可用于解析商品"
                },
                "quantity":{
                  "type":"integer",
                  "minimum":1,
                  "description":"目标商品数量，必须大于等于 1。若用户要改成 0，应调用 remove_from_cart，而不是本工具"
                }
              },
              "required":["userId","conversationId","quantity"],
              "anyOf":[
                {"required":["itemId"]},
                {"required":["spuId"]},
                {"required":["externalRef"]},
                {"required":["lastTurnSpuRefs"]}
              ]
            }
            """;

    private final CartCommandFacade cartCommandFacade;
    private final RagJsonCodec jsonCodec;

    public UpdateCartQtyToolCallback(
            CartCommandFacade cartCommandFacade,
            RagJsonCodec jsonCodec
    ) {
        this.cartCommandFacade = cartCommandFacade;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("""
                        修改当前会话购物车中某个商品的数量。
                        这是写操作，只能在用户明确表达修改数量时调用。
                        优先使用 itemId 精确定位购物车条目；没有 itemId 时再使用 spuId、externalRef 或上一轮商品引用解析。
                        quantity 必须大于等于 1；如果用户想把数量改成 0 或删除商品，应调用 remove_from_cart。
                        不要替用户猜测 userId、conversationId、商品引用或数量。
                        如果用户只是查看购物车、询问价格、询问库存，不要调用本工具。
                        """)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        CartToolInput input = jsonCodec.read(toolInput, CartToolInput.class);
        CartToolOutput output = update(input);
        return jsonCodec.write(output);
    }

    public CartToolOutput update(CartToolInput input) {
        validate(input);

        String externalRef = resolveExternalRef(input);

        CartView cart = cartCommandFacade.updateQuantity(
                input.userId(),
                input.conversationId(),
                input.itemId(),
                input.spuId(),
                externalRef,
                input.quantity()
        );

        return new CartToolOutput(
                TOOL_NAME,
                cart,
                buildFacetsApplied(input, externalRef)
        );
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.CART_OP);
    }

    private void validate(CartToolInput input) {
        if (input == null) {
            throw new IllegalArgumentException("update_cart_qty 输入不能为空");
        }

        if (!StringUtils.hasText(input.userId())) {
            throw new IllegalArgumentException("update_cart_qty.userId 不能为空");
        }

        if (!StringUtils.hasText(input.conversationId())) {
            throw new IllegalArgumentException("update_cart_qty.conversationId 不能为空");
        }

        if (input.quantity() == null) {
            throw new IllegalArgumentException("update_cart_qty.quantity 不能为空");
        }

        if (input.quantity() < 1) {
            throw new IllegalArgumentException("update_cart_qty.quantity 必须大于等于 1；如需删除商品请调用 remove_from_cart");
        }

        if (input.itemId() != null && input.itemId() < 1) {
            throw new IllegalArgumentException("update_cart_qty.itemId 必须大于等于 1");
        }

        if (input.spuId() != null && input.spuId() < 1) {
            throw new IllegalArgumentException("update_cart_qty.spuId 必须大于等于 1");
        }

        String externalRef = resolveExternalRef(input);

        if (input.itemId() == null
                && input.spuId() == null
                && !StringUtils.hasText(externalRef)) {
            throw new IllegalArgumentException("无法解析要修改数量的购物车商品");
        }
    }

    static String resolveExternalRef(CartToolInput input) {
        if (input == null) {
            return null;
        }

        if (StringUtils.hasText(input.externalRef())) {
            return input.externalRef().trim();
        }

        if (input.lastTurnSpuRefs() != null && !input.lastTurnSpuRefs().isEmpty()) {
            return input.lastTurnSpuRefs().get(0);
        }

        return null;
    }

    private Map<String, Object> buildFacetsApplied(
            CartToolInput input,
            String resolvedExternalRef
    ) {
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("action", "update_qty");
        facets.put("quantity", input.quantity());

        if (input.itemId() != null) {
            facets.put("resolvedItemId", input.itemId());
        }

        if (input.spuId() != null) {
            facets.put("resolvedSpuId", input.spuId());
        }

        if (StringUtils.hasText(resolvedExternalRef)) {
            facets.put("resolvedExternalRef", resolvedExternalRef);
        }

        return facets;
    }

    public record CartToolInput(
            String userId,
            String conversationId,
            Long itemId,
            Long spuId,
            String externalRef,
            List<String> lastTurnSpuRefs,
            Integer quantity
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