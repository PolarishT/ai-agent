package com.bytedance.ai.graph.cartmanage.subgraph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.conversation.context.ConversationContextItemStatus;
import com.bytedance.ai.graph.conversation.context.ConversationContextManager;
import com.bytedance.ai.graph.conversation.context.ConversationRuntimeContext;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.application.CartManageSlotFillingService;
import com.bytedance.ai.graph.cartmanage.CartManageSlots;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import com.bytedance.ai.graph.cartmanage.StockResult;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CandidateSelectionResolver;
import com.bytedance.ai.graph.intent.support.SlotKeys;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class CartManageSubgraphFactoryTest {

    private static final String USER_ID = "user-1";
    private static final String CONVERSATION_ID = "conversation-1";

    @Test
    void viewCartExecutesAndFinalizes() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "查看购物车",
                Map.of(SlotKeys.CART_ACTION, "VIEW"),
                CartManageSlots.unknown("unused"),
                cart(item(1L, 101L, "SKU-1", "苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.VIEW_SUCCESS.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("苹果");
    }

    @Test
    void clearCartExecutesAndFinalizes() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "清空购物车",
                Map.of(SlotKeys.CART_ACTION, "CLEAR"),
                CartManageSlots.unknown("unused"),
                cart(item(1L, 101L, "SKU-1", "苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.clearCalled).isTrue();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.CLEAR_SUCCESS.name());
    }

    @Test
    void directAddWithProductAndSkuChecksStockThenExecutes() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "加入购物车",
                Map.of(
                        SlotKeys.CART_ACTION, "ADD",
                        SlotKeys.PRODUCT_ID, "101",
                        SlotKeys.SKU_ID, "SKU-1",
                        SlotKeys.QUANTITY, 2
                ),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("SKU-1");
        assertThat(command.addedQuantity).isEqualTo(2);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void directAddFailureDoesNotReturnAddSuccess() throws Exception {
        StubCartCommand command = new StubCartCommand();
        command.addFailure = CartMutationResult.failure("PRICE_CHANGED", "商品价格已变化，请二次确认后再加入购物车");

        OverAllState state = invoke(
                "加入购物车",
                Map.of(
                        SlotKeys.CART_ACTION, "ADD",
                        SlotKeys.PRODUCT_ID, "101",
                        SlotKeys.SKU_ID, "SKU-1",
                        SlotKeys.QUANTITY, 2
                ),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.FAILED.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("价格发生变化")
                .doesNotContain("已将");
    }

    @Test
    void addByNameWithOneCandidateChecksStockThenExecutes() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "加苹果",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "苹果"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(candidate("101", "SKU-1", "红富士苹果")),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("SKU-1");
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void addByNameWithMultipleCandidatesWaitsForSelection() throws Exception {
        StubContextManager pending = new StubContextManager();

        OverAllState state = invoke(
                "加苹果",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "苹果"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "红富士苹果"),
                        candidate("102", "SKU-2", "青苹果")
                ),
                StockMode.IN_STOCK,
                pending,
                new StubCartCommand()
        );

        assertThat(pending.active).isPresent();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.WAITING_USER_SELECTION.name());
        assertThat(state.value(CartGraphStateKeys.NEED_USER_INPUT, false)).isTrue();
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("请选择要加入购物车的商品");
    }

    @Test
    void testSearchCatalogNoCandidatesReturnsProductNotFound() throws Exception {
        OverAllState state = invoke(
                "加一个不存在的包",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "不存在的包"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                new StubCartCommand()
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.PRODUCT_NOT_FOUND.name());
        assertThat(state.value(CartGraphStateKeys.NEED_USER_INPUT, false)).isTrue();
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .isEqualTo("没有找到该商品，请换个关键词。");
    }

    @Test
    void addByNameWithExpectedPriceMismatchDoesNotOfferInvalidCandidates() throws Exception {
        StubContextManager pending = new StubContextManager();
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "请把商品添加到购物车城市通勤双肩包 15 寸大容量数量为1，商品价格为199的那个",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "城市通勤双肩包 15 寸大容量"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "城市通勤双肩包 15 寸大容量", "259.00", "brand", "color=灰色", "SPU-101"),
                        candidate("102", "SKU-2", "城市通勤双肩包 15 寸大容量", "299.00", "brand", "color=黑色", "SPU-102")
                ),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isNull();
        assertThat(pending.active).isEmpty();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.PRODUCT_CONSTRAINT_NOT_MATCHED.name());
        assertThat(state.value(CartGraphStateKeys.NEED_USER_INPUT, false)).isTrue();
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("找到了类似商品，但没有满足你指定条件的商品")
                .contains("¥199")
                .contains("¥259")
                .contains("¥299")
                .contains("价格不匹配")
                .doesNotContain("请选择");
    }

    @Test
    void testConstraintMismatchDoesNotCreatePending() throws Exception {
        StubContextManager pending = new StubContextManager();

        OverAllState state = invoke(
                "加苹果，价格199",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "苹果"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "红富士苹果", "259.00", "brand", "spec", "SPU-101"),
                        candidate("102", "SKU-2", "青苹果", "299.00", "brand", "spec", "SPU-102")
                ),
                StockMode.IN_STOCK,
                pending,
                new StubCartCommand()
        );

        assertThat(pending.active).isEmpty();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.PRODUCT_CONSTRAINT_NOT_MATCHED.name());
    }

    @Test
    void testConstraintMismatchShowsReason() throws Exception {
        OverAllState state = invoke(
                "加苹果，价格199",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "苹果"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "红富士苹果", "259.00", "brand", "spec", "SPU-101"),
                        candidate("102", "SKU-2", "青苹果", "299.00", "brand", "spec", "SPU-102")
                ),
                StockMode.IN_STOCK,
                new StubContextManager(),
                new StubCartCommand()
        );

        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("找到了类似商品，但没有满足你指定条件的商品")
                .contains("¥199")
                .contains("¥259")
                .contains("¥299")
                .contains("价格不匹配");
    }

    @Test
    void addByNameWithExpectedPriceUniqueMatchAutoAdds() throws Exception {
        StubContextManager pending = new StubContextManager();
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "加苹果，价格259",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "苹果"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "红富士苹果", "259.00", "brand", "spec", "SPU-101"),
                        candidate("102", "SKU-2", "青苹果", "299.00", "brand", "spec", "SPU-102")
                ),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("SKU-1");
        assertThat(command.addedExpectedUnitPrice).isEqualByComparingTo("259");
        assertThat(pending.active).isEmpty();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void testOneMatchedCandidateGoesToStock() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "加苹果，价格259",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "苹果"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "红富士苹果", "259.00", "brand", "spec", "SPU-101"),
                        candidate("102", "SKU-2", "青苹果", "299.00", "brand", "spec", "SPU-102")
                ),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(state.value(CartGraphStateKeys.CART_STATUS, "")).isEqualTo("PRODUCT_SELECTED");
        assertThat(state.value(CartGraphStateKeys.SELECTED_CANDIDATE)).isPresent();
        assertThat(command.addedProductId).isEqualTo("101");
    }

    @Test
    void addByNameWithColorUniqueMatchAutoAdds() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "加一个黑色通勤包",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "通勤包"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "通勤包", "259.00", "brand", "color=灰色", "SPU-101"),
                        candidate("102", "SKU-2", "通勤包", "299.00", "brand", "color=黑色", "SPU-102")
                ),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.addedProductId).isEqualTo("102");
        assertThat(command.addedSkuId).isEqualTo("SKU-2");
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void addByNameWithColorAndPriceUniqueMatchAutoAdds() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "加一个黑色价格299的通勤包",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "通勤包"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "通勤包", "259.00", "brand", "color=黑色", "SPU-101"),
                        candidate("102", "SKU-2", "通勤包", "299.00", "brand", "color=黑色", "SPU-102"),
                        candidate("103", "SKU-3", "通勤包", "299.00", "brand", "color=灰色", "SPU-103")
                ),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.addedProductId).isEqualTo("102");
        assertThat(command.addedSkuId).isEqualTo("SKU-2");
        assertThat(command.addedExpectedUnitPrice).isEqualByComparingTo("299");
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void testMultipleMatchedCandidatesCreatePendingWithMatchedOnly() throws Exception {
        StubContextManager pending = new StubContextManager();

        OverAllState state = invoke(
                "加黑色通勤包",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "通勤包"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "通勤包", "259.00", "brand", "color=黑色", "SPU-101"),
                        candidate("102", "SKU-2", "通勤包", "299.00", "brand", "color=灰色", "SPU-102"),
                        candidate("103", "SKU-3", "通勤包", "399.00", "brand", "color=黑色", "SPU-103")
                ),
                StockMode.IN_STOCK,
                pending,
                new StubCartCommand()
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.WAITING_USER_SELECTION.name());
        assertThat(pending.active).isPresent();
        assertThat(pending.active.orElseThrow().candidates())
                .extracting(ProductCandidate::productId)
                .containsExactly("101", "103");
    }

    @Test
    void testSelectionIndexUsesMatchedCandidatesOnly() throws Exception {
        StubContextManager pending = new StubContextManager();
        invoke(
                "加黑色通勤包",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "通勤包"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "通勤包", "259.00", "brand", "color=黑色", "SPU-101"),
                        candidate("102", "SKU-2", "通勤包", "299.00", "brand", "color=灰色", "SPU-102"),
                        candidate("103", "SKU-3", "通勤包", "399.00", "brand", "color=黑色", "SPU-103")
                ),
                StockMode.IN_STOCK,
                pending,
                new StubCartCommand()
        );
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "选第二个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isEqualTo("103");
        assertThat(command.addedSkuId).isEqualTo("SKU-3");
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void testPriceConstraint199DoesNotReturn259Or299AsSelectable() throws Exception {
        StubContextManager pending = new StubContextManager();

        OverAllState state = invoke(
                "请把商品添加到购物车城市通勤双肩包 15 寸大容量数量为1，商品价格为199的那个",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_NAME, "城市通勤双肩包 15 寸大容量"),
                CartManageSlots.unknown("unused"),
                cart(),
                new StubCatalog(
                        candidate("101", "SKU-1", "城市通勤双肩包 15 寸大容量", "259.00", "brand", "color=灰色", "SPU-101"),
                        candidate("102", "SKU-2", "城市通勤双肩包 15 寸大容量", "299.00", "brand", "color=黑色", "SPU-102")
                ),
                StockMode.IN_STOCK,
                pending,
                new StubCartCommand()
        );

        assertThat(pending.active).isEmpty();
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("¥259")
                .contains("¥299")
                .doesNotContain("1. 城市通勤双肩包")
                .doesNotContain("2. 城市通勤双肩包")
                .doesNotContain("请选择要加入购物车的商品");
    }

    @Test
    void selectionByIndexResolvesCandidateChecksStockThenExecutes() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "红富士苹果"),
                candidate("102", "SKU-2", "青苹果")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "选第 1 个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("SKU-1");
        assertThat(command.addedExpectedUnitPrice).isNull();
        assertThat(pending.completedIds).contains(1L);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void selectionByIndexDoesNotUseCandidatePriceAsExpectedPrice() throws Exception {
        StubContextManager pending = contextManager(List.of(
                new ProductCandidate("101", "SKU-1", "红富士苹果", new BigDecimal("999.00"), "brand", "spec", "SPU-101"),
                new ProductCandidate("102", "SKU-2", "青苹果", new BigDecimal("888.00"), "brand", "spec", "SPU-102")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "选第 2 个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isEqualTo("102");
        assertThat(command.addedSkuId).isEqualTo("SKU-2");
        assertThat(command.addedExpectedUnitPrice).isNull();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void selectionByIndexAddFailureReturnsFailurePromptNotSuccess() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "红富士苹果"),
                candidate("102", "SKU-2", "青苹果")
        ));
        StubCartCommand command = new StubCartCommand();
        command.addFailure = CartMutationResult.failure("PRICE_CHANGED", "商品价格已变化，请二次确认后再加入购物车");

        OverAllState state = invoke(
                "选第 1 个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(pending.completedIds).contains(1L);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.FAILED.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("价格发生变化")
                .doesNotContain("已将");
    }

    @Test
    void implicitThisDoesNotSelectFirstWhenMultipleCandidatesArePending() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "红富士苹果"),
                candidate("102", "SKU-2", "青苹果")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "就这个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isNull();
        assertThat(pending.completedIds).isEmpty();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.WAITING_CLARIFICATION.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("请回复 1-2");
    }

    @Test
    void implicitThisSelectsOnlyCandidateWhenSingleCandidateIsPending() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "红富士苹果")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "就这个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(pending.completedIds).contains(1L);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void removeByIndexExecutes() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "删除第 1 个",
                Map.of(SlotKeys.CART_ACTION, "REMOVE", SlotKeys.ITEM_INDEX, 1),
                CartManageSlots.unknown("unused"),
                cart(item(11L, 101L, "SKU-1", "苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.removedItemId).isEqualTo("11");
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.REMOVE_SUCCESS.name());
    }

    @Test
    void removeFailureDoesNotReturnRemoveSuccess() throws Exception {
        StubCartCommand command = new StubCartCommand();
        command.removeFailure = CartMutationResult.failure("CART_REMOVE_REJECTED", "删除失败");

        OverAllState state = invoke(
                "删除第 1 个",
                Map.of(SlotKeys.CART_ACTION, "REMOVE", SlotKeys.ITEM_INDEX, 1),
                CartManageSlots.unknown("unused"),
                cart(item(11L, 101L, "SKU-1", "苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.FAILED.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .isEqualTo("删除失败");
    }

    @Test
    void removeByProductNameExecutes() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "删除苹果",
                Map.of(SlotKeys.CART_ACTION, "REMOVE", SlotKeys.PRODUCT_NAME, "苹果"),
                CartManageSlots.unknown("unused"),
                cart(item(11L, 101L, "SKU-1", "红富士苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.removedItemId).isEqualTo("11");
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.REMOVE_SUCCESS.name());
    }

    @Test
    void updateByIndexExecutes() throws Exception {
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "把第 2 个改成 3 件",
                Map.of(SlotKeys.CART_ACTION, "UPDATE_QUANTITY", SlotKeys.ITEM_INDEX, 2, SlotKeys.QUANTITY, 3),
                CartManageSlots.unknown("unused"),
                cart(
                        item(11L, 101L, "SKU-1", "苹果", 1),
                        item(12L, 102L, "SKU-2", "牛奶", 1)
                ),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(command.updatedItemId).isEqualTo("12");
        assertThat(command.updatedQuantity).isEqualTo(3);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.UPDATE_SUCCESS.name());
    }

    @Test
    void updateFailureDoesNotReturnUpdateSuccess() throws Exception {
        StubCartCommand command = new StubCartCommand();
        command.updateFailure = CartMutationResult.failure("CART_UPDATE_REJECTED", "更新失败");

        OverAllState state = invoke(
                "把第 1 个改成 3 件",
                Map.of(SlotKeys.CART_ACTION, "UPDATE_QUANTITY", SlotKeys.ITEM_INDEX, 1, SlotKeys.QUANTITY, 3),
                CartManageSlots.unknown("unused"),
                cart(item(11L, 101L, "SKU-1", "苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.FAILED.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .isEqualTo("更新失败");
    }

    @Test
    void clearFailureDoesNotReturnClearSuccess() throws Exception {
        StubCartCommand command = new StubCartCommand();
        command.clearFailure = CartMutationResult.failure("CART_CLEAR_REJECTED", "清空失败");

        OverAllState state = invoke(
                "清空购物车",
                Map.of(SlotKeys.CART_ACTION, "CLEAR"),
                CartManageSlots.unknown("unused"),
                cart(item(1L, 101L, "SKU-1", "苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.FAILED.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .isEqualTo("清空失败");
    }

    @Test
    void missingRemoveOrUpdateTargetWaitsForClarification() throws Exception {
        OverAllState state = invoke(
                "删除一下",
                Map.of(SlotKeys.CART_ACTION, "REMOVE"),
                CartManageSlots.unknown("unused"),
                cart(item(11L, 101L, "SKU-1", "苹果", 1)),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                new StubCartCommand()
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.WAITING_CLARIFICATION.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .doesNotContain("购物车操作已完成")
                .contains("请说明要操作购物车中的第几个商品");
    }

    @Test
    void outOfStockFinalizesWithStockMessage() throws Exception {
        OverAllState state = invoke(
                "加入购物车",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_ID, "101", SlotKeys.SKU_ID, "SKU-1"),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.OUT_OF_STOCK,
                new StubContextManager(),
                new StubCartCommand()
        );

        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.STOCK_NOT_ENOUGH.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("库存不足");
    }

    @Test
    void staleOutOfStockStateDoesNotPolluteNextNormalAdd() throws Exception {
        StubCartCommand command = new StubCartCommand();
        Map<String, Object> staleState = new LinkedHashMap<>();
        staleState.put(CartGraphStateKeys.WORKFLOW_STATUS, CartWorkflowStatus.STOCK_NOT_ENOUGH.name());
        staleState.put(CartGraphStateKeys.NODE_MESSAGE, "旧库存不足");
        staleState.put(CartGraphStateKeys.STOCK_RESULT, StockResult.outOfStock("old", "old-sku", 0));

        OverAllState state = invoke(
                "加入购物车",
                Map.of(SlotKeys.CART_ACTION, "ADD", SlotKeys.PRODUCT_ID, "101", SlotKeys.SKU_ID, "SKU-1"),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                new StubContextManager(),
                command,
                staleState
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .doesNotContain("旧库存不足");
    }

    @Test
    void parseSelectionIndexSupportsChineseAndArabicOrdinalExpressions() {
        CandidateSelectionResolver resolver = new CandidateSelectionResolver(StubCandidateSelectionLlm.unmatched());

        assertThat(resolver.parseSelectionIndex("我选择第一个", 2)).isEqualTo(1);
        assertThat(resolver.parseSelectionIndex("我要第二个", 3)).isEqualTo(2);
        assertThat(resolver.parseSelectionIndex("选第 1 个", 2)).isEqualTo(1);
        assertThat(resolver.parseSelectionIndex("1", 2)).isEqualTo(1);
        assertThat(resolver.parseSelectionIndex("就这个", 1)).isEqualTo(1);
        assertThat(resolver.parseSelectionIndex("就这个", 2)).isEqualTo(-1);
    }

    @Test
    void attributeMatchSelectsUniqueCandidateByColor() {
        CandidateSelectionResolver resolver = new CandidateSelectionResolver(StubCandidateSelectionLlm.unmatched());
        List<ProductCandidate> candidates = List.of(
                candidate("101", "SKU-1", "通勤包", "NorthFace", "color=黑色", "SPU-101"),
                candidate("102", "SKU-2", "通勤包", "NorthFace", "color=藏青", "SPU-102")
        );

        assertThat(resolver.attributeMatch("我要黑色的", candidates).selectedIndex()).isEqualTo(1);
        assertThat(resolver.attributeMatch("藏青色那个", candidates).selectedIndex()).isEqualTo(2);
    }

    @Test
    void attributeMatchReportsAmbiguousWhenMultipleCandidatesMatch() {
        CandidateSelectionResolver resolver = new CandidateSelectionResolver(StubCandidateSelectionLlm.unmatched());
        List<ProductCandidate> candidates = List.of(
                candidate("101", "SKU-1", "通勤包", "NorthFace", "color=黑色", "SPU-101"),
                candidate("102", "SKU-2", "通勤包", "NorthFace", "color=藏青", "SPU-102")
        );

        assertThat(resolver.attributeMatch("NorthFace 那个", candidates).status())
                .isEqualTo(CandidateSelectionResolver.CandidateSelectionStatus.AMBIGUOUS);
    }

    @Test
    void selectionByChineseOrdinalResolvesCandidateChecksStockThenExecutes() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "红富士苹果"),
                candidate("102", "SKU-2", "青苹果")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "我选择第一个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("SKU-1");
        assertThat(pending.completedIds).contains(1L);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void selectionByCandidateAttributeChecksStockThenExecutes() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "轻量通勤双肩包", "NorthFace", "color=黑色", "SPU-101"),
                candidate("102", "SKU-2", "轻量通勤双肩包", "NorthFace", "color=藏青", "SPU-102")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "我要黑色的",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("SKU-1");
        assertThat(pending.completedIds).contains(1L);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void llmFallbackValidIndexChecksStockThenExecutes() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "红富士苹果"),
                candidate("102", "SKU-2", "青苹果")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "就按你推荐的那个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command,
                new StubCandidateSelectionLlm(Optional.of(1))
        );

        assertThat(command.addedProductId).isEqualTo("101");
        assertThat(command.addedSkuId).isEqualTo("SKU-1");
        assertThat(pending.completedIds).contains(1L);
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.ADD_SUCCESS.name());
    }

    @Test
    void llmFallbackNegativeIndexWaitsForClarification() throws Exception {
        StubContextManager pending = contextManager(List.of(
                candidate("101", "SKU-1", "红富士苹果"),
                candidate("102", "SKU-2", "青苹果")
        ));
        StubCartCommand command = new StubCartCommand();

        OverAllState state = invoke(
                "就按你推荐的那个",
                Map.of(),
                CartManageSlots.unknown("unused"),
                cart(),
                ProductCatalogResolver.empty(),
                StockMode.IN_STOCK,
                pending,
                command,
                new StubCandidateSelectionLlm(Optional.of(-1))
        );

        assertThat(command.addedProductId).isNull();
        assertThat(pending.completedIds).isEmpty();
        assertThat(state.value(CartGraphStateKeys.WORKFLOW_STATUS, ""))
                .isEqualTo(CartWorkflowStatus.WAITING_CLARIFICATION.name());
        assertThat(state.value(CartGraphStateKeys.NODE_MESSAGE, ""))
                .contains("请回复 1-2 之间的序号");
    }

    private OverAllState invoke(
            String message,
            Map<String, Object> slots,
            CartManageSlots filledSlots,
            CartView cart,
            ProductCatalogResolver catalogResolver,
            StockMode stockMode,
            StubContextManager pendingRepository,
            StubCartCommand command
    ) throws Exception {
        return invoke(message, slots, filledSlots, cart, catalogResolver, stockMode, pendingRepository, command, Map.of());
    }

    private OverAllState invoke(
            String message,
            Map<String, Object> slots,
            CartManageSlots filledSlots,
            CartView cart,
            ProductCatalogResolver catalogResolver,
            StockMode stockMode,
            StubContextManager pendingRepository,
            StubCartCommand command,
            Map<String, Object> extraState
    ) throws Exception {
        return invoke(message, slots, filledSlots, cart, catalogResolver, stockMode, pendingRepository, command,
                extraState, StubCandidateSelectionLlm.unmatched());
    }

    private OverAllState invoke(
            String message,
            Map<String, Object> slots,
            CartManageSlots filledSlots,
            CartView cart,
            ProductCatalogResolver catalogResolver,
            StockMode stockMode,
            StubContextManager pendingRepository,
            StubCartCommand command,
            CandidateSelectionLlmService candidateSelectionLlmService
    ) throws Exception {
        return invoke(message, slots, filledSlots, cart, catalogResolver, stockMode, pendingRepository, command,
                Map.of(), candidateSelectionLlmService);
    }

    private OverAllState invoke(
            String message,
            Map<String, Object> slots,
            CartManageSlots filledSlots,
            CartView cart,
            ProductCatalogResolver catalogResolver,
            StockMode stockMode,
            StubContextManager pendingRepository,
            StubCartCommand command,
            Map<String, Object> extraState,
            CandidateSelectionLlmService candidateSelectionLlmService
    ) throws Exception {
        CartManageSubgraphFactory factory = new CartManageSubgraphFactory(
                (userId, conversationId) -> cart,
                command,
                (productId, skuId, requested) -> stockMode == StockMode.IN_STOCK
                        ? StockResult.inStock(productId, skuId, requested)
                        : StockResult.outOfStock(productId, skuId, 0),
                catalogResolver,
                new StubCatalogQueryFacade(),
                pendingRepository,
                new StubSlotFilling(filledSlots),
                candidateSelectionLlmService
        );
        Map<String, Object> initialState = new LinkedHashMap<>(extraState);
        initialState.put(GuideGraphStateKeys.USER_ID, USER_ID);
        initialState.put(GuideGraphStateKeys.CONVERSATION_ID, CONVERSATION_ID);
        initialState.put(GuideGraphStateKeys.MESSAGE, message);
        initialState.put(GuideGraphStateKeys.INTENT_SLOTS, slots);
        return factory.build().compile().invoke(initialState).orElseThrow();
    }

    private static ProductCandidate candidate(String productId, String skuId, String name) {
        return new ProductCandidate(productId, skuId, name, new BigDecimal("9.90"), "brand", "spec", "SPU-" + productId);
    }

    private static ProductCandidate candidate(
            String productId,
            String skuId,
            String name,
            String price,
            String brief,
            String spec,
            String externalRef
    ) {
        return new ProductCandidate(productId, skuId, name, new BigDecimal(price), brief, spec, externalRef);
    }

    private static ProductCandidate candidate(
            String productId,
            String skuId,
            String name,
            String brief,
            String spec,
            String externalRef
    ) {
        return new ProductCandidate(productId, skuId, name, new BigDecimal("9.90"), brief, spec, externalRef);
    }

    private static CartItemView item(Long itemId, Long spuId, String skuId, String title, int quantity) {
        Long parsedSkuId = parseLongOrNull(skuId);
        return new CartItemView(itemId, spuId, parsedSkuId, "SPU-" + spuId + ":" + skuId, title, "brand", null, quantity,
                new BigDecimal("9.90"), new BigDecimal("9.90"), 10);
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static CartView cart(CartItemView... items) {
        List<CartItemView> itemList = new ArrayList<>(List.of(items));
        return new CartView("cart-1", USER_ID, CONVERSATION_ID, CartState.IN_CART, "CNY",
                BigDecimal.ZERO, itemList.size(), Map.of(), itemList);
    }

    private static StubContextManager contextManager(List<ProductCandidate> candidates) {
        StubContextManager repository = new StubContextManager();
        repository.active = Optional.of(new TestPending(
                1L,
                1,
                candidates
        ));
        return repository;
    }

    private enum StockMode {
        IN_STOCK,
        OUT_OF_STOCK
    }

    private record StubCatalog(List<ProductCandidate> candidates) implements ProductCatalogResolver {
        StubCatalog(ProductCandidate... candidates) {
            this(List.of(candidates));
        }

        @Override
        public List<ProductCandidate> searchCandidates(String productName, int limit) {
            return candidates;
        }
    }

    private static final class StubSlotFilling extends CartManageSlotFillingService {
        private final CartManageSlots slots;

        private StubSlotFilling(CartManageSlots slots) {
            super(null);
            this.slots = slots;
        }

        @Override
        public CartManageSlots extract(String userMessage, String conversationMemory) {
            return slots;
        }
    }

    private static final class StubCartCommand implements CartCommandService {
        String addedProductId;
        String addedSkuId;
        Integer addedQuantity;
        BigDecimal addedExpectedUnitPrice;
        String removedItemId;
        String updatedItemId;
        Integer updatedQuantity;
        boolean clearCalled;
        CartMutationResult addFailure;
        CartMutationResult removeFailure;
        CartMutationResult updateFailure;
        CartMutationResult clearFailure;

        @Override
        public CartMutationResult addItem(
                String userId,
                String conversationId,
                String productId,
                String skuId,
                int quantity,
                BigDecimal expectedUnitPrice
        ) {
            this.addedProductId = productId;
            this.addedSkuId = skuId;
            this.addedQuantity = quantity;
            this.addedExpectedUnitPrice = expectedUnitPrice;
            if (addFailure != null) {
                return addFailure;
            }
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult removeItem(String userId, String conversationId, String cartItemId) {
            this.removedItemId = cartItemId;
            if (removeFailure != null) {
                return removeFailure;
            }
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult updateQuantity(String userId, String conversationId, String cartItemId, int quantity) {
            this.updatedItemId = cartItemId;
            this.updatedQuantity = quantity;
            if (updateFailure != null) {
                return updateFailure;
            }
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult clearCart(String userId, String conversationId) {
            this.clearCalled = true;
            if (clearFailure != null) {
                return clearFailure;
            }
            return CartMutationResult.ok(null);
        }
    }

    private record StubCandidateSelectionLlm(Optional<Integer> index) implements CandidateSelectionLlmService {
        static StubCandidateSelectionLlm unmatched() {
            return new StubCandidateSelectionLlm(Optional.empty());
        }

        @Override
        public Optional<Integer> resolveIndex(String userMessage, List<ProductCandidate> candidates) {
            return index;
        }
    }

    private record TestPending(Long id, Integer quantity, List<ProductCandidate> candidates) {
    }

    private static final class StubCatalogQueryFacade implements CatalogQueryFacade {
        @Override
        public CatalogProductView getProduct(Long productId) {
            return new CatalogProductView(productId, "商品" + productId, "brand", "category", null,
                    new BigDecimal("9.90"), new BigDecimal("9.90"), new BigDecimal("9.90"),
                    10, "", "ACTIVE", Map.of(), Map.of(), listSkus(productId), OffsetDateTime.now(), OffsetDateTime.now());
        }

        @Override
        public List<CatalogSkuView> listSkus(Long productId) {
            return List.of(new CatalogSkuView(200L + productId, "SKU-" + productId, Map.of(),
                    new BigDecimal("9.90"), 10, "ACTIVE"));
        }
    }

    private static final class StubContextManager implements ConversationContextManager {
        Optional<TestPending> active = Optional.empty();
        List<Long> completedIds = new ArrayList<>();
        List<Long> cancelledIds = new ArrayList<>();
        long sequence = 1L;

        @Override
        public ConversationRuntimeContext load(String userId, String conversationId) {
            ConversationRuntimeContext.PendingClarification clarification = active
                    .map(pending -> new ConversationRuntimeContext.PendingClarification(
                            pending.id(),
                            "CART_CANDIDATE_SELECTION",
                            "cart_manage_workflow",
                            pending.quantity(),
                            toContextCandidates(pending.candidates()),
                            LocalDateTime.now().plusHours(1),
                            Map.of()
                    ))
                    .orElse(null);
            return new ConversationRuntimeContext(
                    1L,
                    userId,
                    conversationId,
                    List.of(),
                    null,
                    List.of(),
                    null,
                    null,
                    clarification,
                    null,
                    Map.of(),
                    Map.of(),
                    List.of()
            );
        }

        @Override
        public void saveProductCandidates(String userId, String conversationId, String sourceTurnId,
                                          String sourceWorkflow,
                                          List<ConversationRuntimeContext.ProductCandidateItem> candidates,
                                          LocalDateTime expiresAt) {
        }

        @Override
        public void saveTaskChain(String userId, String conversationId, String sourceTurnId,
                                  String sourceWorkflow,
                                  ConversationRuntimeContext.TaskChain taskChain,
                                  LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.TaskChain loadTaskChain(String userId, String conversationId,
                                                                  String taskChainId) {
            return null;
        }

        @Override
        public boolean markPlanTask(String userId, String conversationId, String taskChainId, String taskId,
                                    String newTaskStatus, ConversationRuntimeContext.TaskStep executedStep,
                                    String turnId) {
            return false;
        }

        @Override
        public boolean transitionChainStatus(String userId, String conversationId, String taskChainId,
                                             String newChainStatus, String turnId) {
            return false;
        }

        @Override
        public void updateFocus(String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                                ConversationRuntimeContext.Focus focus, LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.PendingClarification savePendingClarification(
                String userId,
                String conversationId,
                String sourceTurnId,
                String sourceWorkflow,
                ConversationRuntimeContext.PendingClarification clarification,
                LocalDateTime expiresAt
        ) {
            Long id = sequence++;
            active = Optional.of(new TestPending(id, clarification.quantity(),
                    clarification.candidates().stream()
                            .map(candidate -> new ProductCandidate(
                                    candidate.productId(),
                                    candidate.skuId(),
                                    candidate.productName(),
                                    candidate.price(),
                                    candidate.brief(),
                                    candidate.spec(),
                                    candidate.externalRef()))
                            .toList()));
            return new ConversationRuntimeContext.PendingClarification(
                    id,
                    clarification.clarificationType(),
                    clarification.sourceWorkflow(),
                    clarification.quantity(),
                    clarification.candidates(),
                    expiresAt,
                    clarification.payload()
            );
        }

        @Override
        public void consumePendingClarification(Long contextItemId) {
            completedIds.add(contextItemId);
            active = Optional.empty();
        }

        @Override
        public void updateCartSnapshot(String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                                       ConversationRuntimeContext.CartSnapshot cartSnapshot, LocalDateTime expiresAt) {
        }

        @Override
        public ConversationRuntimeContext.OrderContext updateOrderContext(String userId, String conversationId,
                                                                          String sourceTurnId, String sourceWorkflow,
                                                                          ConversationRuntimeContext.OrderContext orderContext,
                                                                          LocalDateTime expiresAt) {
            return orderContext;
        }

        @Override
        public void updateLastTurn(String userId, String conversationId, String sourceTurnId, String sourceWorkflow,
                                   ConversationRuntimeContext.LastTurn lastTurn, LocalDateTime expiresAt) {
        }

        @Override
        public boolean transitionOrderContextStatus(Long contextItemId, String expectedOrderStatus,
                                                    ConversationRuntimeContext.OrderContext orderContext,
                                                    ConversationContextItemStatus itemStatus) {
            return false;
        }

        @Override
        public void markContextItemStatus(Long contextItemId, ConversationContextItemStatus status) {
            cancelledIds.add(contextItemId);
            active = Optional.empty();
        }

        private List<ConversationRuntimeContext.ProductCandidateItem> toContextCandidates(List<ProductCandidate> candidates) {
            List<ConversationRuntimeContext.ProductCandidateItem> result = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                ProductCandidate candidate = candidates.get(i);
                result.add(new ConversationRuntimeContext.ProductCandidateItem(
                        null,
                        i + 1,
                        candidate.productId(),
                        candidate.skuId(),
                        candidate.productName(),
                        candidate.price(),
                        candidate.brief(),
                        candidate.spec(),
                        candidate.externalRef(),
                        null,
                        Map.of()
                ));
            }
            return result;
        }
    }
}
