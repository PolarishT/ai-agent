package com.bytedance.ai.graph.cartmanage;

import com.bytedance.ai.graph.cartmanage.application.CartManageWorkflowNode;
import com.bytedance.ai.graph.cartmanage.application.CartManageSlotFillingService;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import com.bytedance.ai.graph.cartmanage.application.InventoryQueryService;
import com.bytedance.ai.graph.cartmanage.application.CartQueryService;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.NodeRunStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CartManageWorkflowNodeTest {

    private static final String USER_ID = "u-1";
    private static final String CONVERSATION_ID = "c-1";

    @Test
    void removesSecondItemWhenSlotProvidesIndex() {
        CartView cart = cart(item(101L, "洗面奶"), item(102L, "面膜"));
        StubSlotFilling slots = new StubSlotFilling(slots(CartManageAction.REMOVE_ITEM, 2, null, null));
        StubCartCommand command = new StubCartCommand();

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                slots, stubQuery(cart), command, alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.SUCCESS);
        assertThat(command.removedItemId).isEqualTo("102");
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.action()).isEqualTo(CartManageAction.REMOVE_ITEM);
        assertThat(payload.targetItem().itemId()).isEqualTo(102L);
    }

    @Test
    void updatesQuantityForSingleItemWithContextualReference() {
        CartView cart = cart(item(201L, "洗面奶"));
        StubSlotFilling slots = new StubSlotFilling(
                slotsWithQuantity(CartManageAction.UPDATE_QUANTITY, null, null, true, 2));
        StubCartCommand command = new StubCartCommand();

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                slots, stubQuery(cart), command, alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.SUCCESS);
        assertThat(command.updatedItemId).isEqualTo("201");
        assertThat(command.updatedQuantity).isEqualTo(2);
    }

    @Test
    void asksClarificationWhenQuantityRequestedAcrossMultipleItems() {
        CartView cart = cart(item(301L, "洗面奶"), item(302L, "面膜"));
        StubSlotFilling slots = new StubSlotFilling(
                slotsWithQuantity(CartManageAction.UPDATE_QUANTITY, null, null, true, 2));

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                slots, stubQuery(cart), new StubCartCommand(), alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.WAITING_CLARIFICATION);
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.clarifyQuestion()).contains("哪个商品");
    }

    @Test
    void removesItemMatchedByProductName() {
        CartView cart = cart(item(401L, "氨基酸洗面奶"), item(402L, "面膜"));
        StubSlotFilling slots = new StubSlotFilling(
                slots(CartManageAction.REMOVE_ITEM, null, "洗面奶", null));
        StubCartCommand command = new StubCartCommand();

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                slots, stubQuery(cart), command, alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.SUCCESS);
        assertThat(command.removedItemId).isEqualTo("401");
    }

    @Test
    void clearCartReturnsWaitingConfirmationWithoutInvokingClear() {
        CartView cart = cart(item(501L, "洗面奶"));
        StubSlotFilling slots = new StubSlotFilling(slots(CartManageAction.CLEAR_CART, null, null, null));
        StubCartCommand command = new StubCartCommand();

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                slots, stubQuery(cart), command, alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.WAITING_CONFIRMATION);
        assertThat(command.clearCalled).isFalse();
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.pendingConfirmAction()).isEqualTo("CLEAR_CART");
        assertThat(payload.clarifyQuestion()).contains("清空整个购物车");
    }

    @Test
    void emptyCartReturnsClarification() {
        CartView empty = cart();
        StubSlotFilling slots = new StubSlotFilling(slots(CartManageAction.VIEW_CART, null, null, null));

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                slots, stubQuery(empty), new StubCartCommand(), alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.WAITING_CLARIFICATION);
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.clarifyQuestion()).contains("空的");
    }

    @Test
    void updateQuantityFailsWhenInventoryInsufficient() {
        CartView cart = cart(item(601L, "洗面奶"));
        StubSlotFilling slots = new StubSlotFilling(
                slotsWithQuantity(CartManageAction.UPDATE_QUANTITY, null, null, true, 5));
        StubCartCommand command = new StubCartCommand();
        InventoryQueryService outOfStock = (productId, skuId, requested) ->
                StockResult.outOfStock(productId, skuId, 2);

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                slots, stubQuery(cart), command, outOfStock);

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.FAILED);
        assertThat(command.updatedItemId).isNull();
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.errorCode()).isEqualTo("INVENTORY_INSUFFICIENT");
        assertThat(payload.errorMessage()).contains("库存不足");
    }

    @Test
    void addActionFromIntentSlotsReturnsClarificationWithoutNpe() {
        // Empty cart; main intent rewrote ADD_TO_CART to CART_MANAGE with cart_action=ADD and a
        // product_name from the LLM. Filler returns UNKNOWN because its prompt doesn't cover ADD.
        CartView emptyCart = cart();
        StubSlotFilling filler = new StubSlotFilling(CartManageSlots.unknown("filler does not handle ADD"));
        Map<String, Object> intentSlots = new HashMap<>();
        intentSlots.put("cart_action", "ADD");
        intentSlots.put("product_name", "轻量通勤双肩包 14 寸防水");

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                filler, stubQuery(emptyCart), new StubCartCommand(), alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialStateWithIntentSlots(intentSlots));

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.WAITING_CLARIFICATION);
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.action()).isEqualTo(CartManageAction.ADD);
        assertThat(payload.slots().productName()).isEqualTo("轻量通勤双肩包 14 寸防水");
        assertThat(payload.clarifyQuestion()).isNotBlank();
        assertThat(result.stateUpdates()).containsKeys(
                GuideGraphStateKeys.WORKFLOW_RESULT,
                GuideGraphStateKeys.EVIDENCE,
                GuideGraphStateKeys.BUSINESS_RESULT
        );
    }

    @Test
    void addWithMultipleProductCandidatesReturnsChooseClarification() {
        CartView emptyCart = cart();
        StubSlotFilling filler = new StubSlotFilling(slots(CartManageAction.ADD, null, "双肩包", null));
        StubProductCatalogResolver resolver = new StubProductCatalogResolver(List.of(
                candidate("101", "201", "轻量通勤双肩包 14 寸", "黑色"),
                candidate("102", "202", "防水电脑双肩包 14 寸", "蓝色")
        ));

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                filler, stubQuery(emptyCart), new StubCartCommand(), alwaysInStock(), resolver);

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.WAITING_CLARIFICATION);
        assertThat(result.metadata()).containsEntry("clarifyReason", "need_choose_product_candidate");
        assertThat(result.stateUpdates()).containsEntry(GuideGraphStateKeys.NEED_USER_INPUT, true);
        assertThat(result.stateUpdates()).containsKey(GuideGraphStateKeys.PRODUCT_CANDIDATES);
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.clarifyQuestion()).contains("选第 1 个");
        assertThat(payload.productCandidates()).hasSize(2);
    }

    @Test
    void addWithNoProductCandidateReturnsProductNotFoundClarification() {
        CartView emptyCart = cart();
        StubSlotFilling filler = new StubSlotFilling(slots(CartManageAction.ADD, null, "不存在商品", null));

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                filler, stubQuery(emptyCart), new StubCartCommand(), alwaysInStock(),
                new StubProductCatalogResolver(List.of()));

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.WAITING_CLARIFICATION);
        assertThat(result.metadata()).containsEntry("clarifyReason", "product_not_found");
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.clarifyQuestion()).contains("没有找到");
    }

    @Test
    void addWithUniqueCandidateAndStockAddsToCart() {
        CartView emptyCart = cart();
        StubSlotFilling filler = new StubSlotFilling(slots(CartManageAction.ADD, null, "双肩包", null));
        StubCartCommand command = new StubCartCommand();

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                filler, stubQuery(emptyCart), command, alwaysInStock(),
                new StubProductCatalogResolver(List.of(candidate("101", "201", "轻量通勤双肩包 14 寸", "黑色"))));

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.SUCCESS);
        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("201");
        assertThat(command.addedQuantity).isEqualTo(1);
        CartManageWorkflowResult payload = (CartManageWorkflowResult) result.workflowResult();
        assertThat(payload.productCandidates()).hasSize(1);
    }

    @Test
    void clarificationWithMostlyNullPayloadDoesNotNpe() {
        // Trigger the empty-cart clarification path with a slot filler that returns mostly nulls.
        // Prior to the fix this NPE'd because evidence/businessResult used Map.of with null values.
        CartView emptyCart = cart();
        CartManageSlots nullishSlots = new CartManageSlots(
                CartManageAction.VIEW_CART, null, null, null, null, null, null, null);
        StubSlotFilling filler = new StubSlotFilling(nullishSlots);

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                filler, stubQuery(emptyCart), new StubCartCommand(), alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialState());

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.WAITING_CLARIFICATION);
        assertThat(result.stateUpdates().get(GuideGraphStateKeys.EVIDENCE)).isNotNull();
        assertThat(result.stateUpdates().get(GuideGraphStateKeys.BUSINESS_RESULT)).isNotNull();
    }

    @Test
    void mainIntentCartActionOverridesUnknownFiller() {
        // Filler couldn't decide; cart has one item; intent slots carry cart_action=REMOVE plus
        // product_name hint. The merge should drive REMOVE_ITEM against the resolved single item.
        CartView cart = cart(item(701L, "轻便雨伞"));
        StubSlotFilling filler = new StubSlotFilling(CartManageSlots.unknown("filler unsure"));
        Map<String, Object> intentSlots = new HashMap<>();
        intentSlots.put("cart_action", "REMOVE");
        intentSlots.put("product_name", "雨伞");
        StubCartCommand command = new StubCartCommand();

        CartManageWorkflowNode node = new CartManageWorkflowNode(
                filler, stubQuery(cart), command, alwaysInStock());

        GuideNodeExecutionResult result = node.execute(initialStateWithIntentSlots(intentSlots));

        assertThat(result.statusOverride()).isEqualTo(NodeRunStatus.SUCCESS);
        assertThat(command.removedItemId).isEqualTo("701");
    }

    // ---------- helpers ----------

    private OverAllState initialState() {
        return new OverAllState(baseState());
    }

    private OverAllState initialStateWithIntentSlots(Map<String, Object> intentSlots) {
        Map<String, Object> data = baseState();
        data.put(GuideGraphStateKeys.INTENT_SLOTS, intentSlots);
        return new OverAllState(data);
    }

    private Map<String, Object> baseState() {
        Map<String, Object> data = new HashMap<>();
        data.put(GuideGraphStateKeys.USER_ID, USER_ID);
        data.put(GuideGraphStateKeys.CONVERSATION_ID, CONVERSATION_ID);
        data.put(GuideGraphStateKeys.MESSAGE, "test message");
        return data;
    }

    private CartManageSlots slots(CartManageAction action, Integer itemIndex, String productName, String skuId) {
        return new CartManageSlots(action, itemIndex, productName, null, skuId, null, false, "stub");
    }

    private CartManageSlots slotsWithQuantity(
            CartManageAction action, Integer itemIndex, String productName, boolean ctx, Integer qty
    ) {
        return new CartManageSlots(action, itemIndex, productName, null, null, qty, ctx, "stub");
    }

    private CartItemView item(Long itemId, String title) {
        return new CartItemView(itemId, itemId, null, "ext-" + itemId, title, "brand", null, 1,
                new BigDecimal("9.9"), new BigDecimal("9.9"), 10);
    }

    private CartView cart(CartItemView... items) {
        List<CartItemView> list = new ArrayList<>(List.of(items));
        BigDecimal subtotal = list.stream()
                .map(CartItemView::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartView("cart-1", USER_ID, CONVERSATION_ID, CartState.IN_CART, "CNY",
                subtotal, list.size(), Map.of(), list);
    }

    private CartQueryService stubQuery(CartView cart) {
        return (userId, conversationId) -> cart;
    }

    private InventoryQueryService alwaysInStock() {
        return (productId, skuId, requested) -> StockResult.inStock(productId, skuId, requested);
    }

    private ProductCandidate candidate(String productId, String skuId, String name, String spec) {
        return new ProductCandidate(productId, skuId, name, new BigDecimal("99.00"), "brand", spec, "SPU-" + productId);
    }

    private static final class StubSlotFilling extends CartManageSlotFillingService {
        private final CartManageSlots slots;

        StubSlotFilling(CartManageSlots slots) {
            // Pass null ChatClient — never used because we override extract().
            super(null);
            this.slots = slots;
        }

        @Override
        public CartManageSlots extract(String userMessage, String conversationMemory) {
            return slots;
        }
    }

    private static final class StubCartCommand implements CartCommandService {
        String removedItemId;
        String updatedItemId;
        Integer updatedQuantity;
        String addedProductId;
        String addedSkuId;
        Integer addedQuantity;
        boolean clearCalled;

        @Override
        public CartMutationResult addItem(
                String userId,
                String conversationId,
                String productId,
                String skuId,
                int quantity,
                BigDecimal expectedUnitPrice
        ) {
            addedProductId = productId;
            addedSkuId = skuId;
            addedQuantity = quantity;
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult removeItem(String userId, String conversationId, String cartItemId) {
            removedItemId = cartItemId;
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult updateQuantity(
                String userId, String conversationId, String cartItemId, int quantity
        ) {
            updatedItemId = cartItemId;
            updatedQuantity = quantity;
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult clearCart(String userId, String conversationId) {
            clearCalled = true;
            return CartMutationResult.ok(null);
        }
    }

    private record StubProductCatalogResolver(List<ProductCandidate> candidates) implements ProductCatalogResolver {
        @Override
        public List<ProductCandidate> searchCandidates(String productName, int limit) {
            return candidates;
        }
    }
}
