package com.bytedance.ai.graph.cartmanage.application;

import com.bytedance.ai.graph.cartmanage.StockResult;
import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.CartManageWorkflowResult;
import com.bytedance.ai.graph.cartmanage.CartManageSlots;
import com.bytedance.ai.graph.cartmanage.CartManageAction;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.orchestration.GuideGraphContextSupport;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import com.bytedance.ai.graph.api.GuideNodeExecutionResult;
import com.bytedance.ai.graph.api.NodeRunStatus;
import com.bytedance.ai.graph.intent.support.SlotKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * cart_manage_workflow main node.
 *
 * <p>Single graph node — no sub-graph. Internally orchestrates: slot filling → load cart → resolve
 * cart item → branch by action → call cart service → build {@link CartManageWorkflowResult}.
 *
 * <p>Slot resolution layers (merged in order, earlier wins for each field):
 * <ol>
 *   <li>Cart-manage slot-filling LLM (action / itemIndex / quantity / contextualReference / etc.)</li>
 *   <li>Main-intent decision slots from state ({@code intentSlots}) — especially the
 *       {@code cart_action} override populated when the main router emitted a legacy granular
 *       intent (ADD_TO_CART / REMOVE_FROM_CART / UPDATE_CART_ITEM) that was rewritten to CART_MANAGE.</li>
 * </ol>
 *
 * <p>Hard contracts:
 * <ul>
 *   <li>LLM never decides {@code cartItemId}; the server resolves it from the live cart.</li>
 *   <li>LLM never invents productId / skuId / item title.</li>
 *   <li>CLEAR_CART never actually clears; it returns WAITING_CONFIRMATION for a follow-up turn.</li>
 *   <li>UPDATE_QUANTITY is gated by an inventory probe (see {@link InventoryQueryService}).</li>
 *   <li>ADD resolves catalog candidates before writing cart state.</li>
 * </ul>
 */
@Component
public class CartManageWorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(CartManageWorkflowNode.class);

    private static final String CLARIFY_EMPTY_CART = "你的购物车目前是空的，没有可以操作的商品。";
    private static final String CLARIFY_AMBIGUOUS_TARGET =
            "请说明要操作购物车里的哪个商品，例如\"删除第二个商品\"或\"把洗面奶数量改成 2\"。";
    private static final String CLARIFY_UNKNOWN_ACTION =
            "我没太理解你的购物车操作。你想查看购物车、删除商品、修改数量，还是清空购物车？";
    private static final String CLARIFY_INDEX_OUT_OF_RANGE =
            "购物车里没有这个序号的商品，请确认要操作的位置。";
    private static final String CLARIFY_PRODUCT_NOT_FOUND =
            "购物车里没找到符合\"%s\"的商品，请确认商品名或换种说法。";
    private static final String CLARIFY_PRODUCT_AMBIGUOUS =
            "购物车里有多个匹配\"%s\"的商品，请说明要操作第几个。";
    private static final String CLARIFY_QUANTITY_INVALID =
            "数量必须是大于等于 1 的整数，请确认要改成多少。";
    private static final String CLARIFY_ADD_NEEDS_PRODUCT_REF =
            "请告诉我你想加入购物车的具体商品名或商品 ID。";
    private static final String CLARIFY_ADD_PRODUCT_NOT_FOUND =
            "我没有找到你说的商品，请换个商品名或提供更具体的品牌、型号、尺寸。";
    private static final String CLARIFY_ADD_CHOOSE_CANDIDATE =
            "我找到几款可能符合的商品，请选择要加入购物车的商品：";
    private static final String CONFIRM_CLEAR_CART =
            "你确认要清空整个购物车吗？这个操作不可撤销。";

    private static final String PENDING_CONFIRM_ACTION_CLEAR_CART = "CLEAR_CART";

    private final CartManageSlotFillingService slotFillingService;
    private final CartQueryService cartQueryService;
    private final CartCommandService cartCommandService;
    private final InventoryQueryService inventoryQueryService;
    private final ProductCatalogResolver productCatalogResolver;

    @Autowired
    public CartManageWorkflowNode(
            CartManageSlotFillingService slotFillingService,
            CartQueryService cartQueryService,
            CartCommandService cartCommandService,
            InventoryQueryService inventoryQueryService,
            ProductCatalogResolver productCatalogResolver
    ) {
        this.slotFillingService = slotFillingService;
        this.cartQueryService = cartQueryService;
        this.inventoryQueryService = inventoryQueryService;
        this.cartCommandService = cartCommandService;
        this.productCatalogResolver = productCatalogResolver == null
                ? ProductCatalogResolver.empty()
                : productCatalogResolver;
    }

    public CartManageWorkflowNode(
            CartManageSlotFillingService slotFillingService,
            CartQueryService cartQueryService,
            CartCommandService cartCommandService,
            InventoryQueryService inventoryQueryService
    ) {
        this(slotFillingService, cartQueryService, cartCommandService, inventoryQueryService,
                ProductCatalogResolver.empty());
    }

    public GuideNodeExecutionResult execute(OverAllState state) {
        String userId = requiredString(state, GuideGraphStateKeys.USER_ID);
        String conversationId = requiredString(state, GuideGraphStateKeys.CONVERSATION_ID);
        String userMessage = state.value(GuideGraphStateKeys.MESSAGE, "");
        String memory = renderMemory(state);
        Map<String, Object> intentSlots = readIntentSlots(state);

        CartManageSlots fillerSlots = slotFillingService.extract(userMessage, memory);
        CartManageSlots slots = mergeWithIntentSlots(fillerSlots, intentSlots);
        CartView cart = cartQueryService.getUserCart(userId, conversationId);

        log.atInfo()
                .addKeyValue("event.name", "cart_manage.workflow.slots")
                .addKeyValue("cart.user_id", userId)
                .addKeyValue("cart.conversation_id", conversationId)
                .addKeyValue("cart.action", slots.action() == null ? "UNKNOWN" : slots.action().name())
                .addKeyValue("cart.item_index", slots.itemIndex())
                .addKeyValue("cart.product_name", slots.productName())
                .addKeyValue("cart.product_id", slots.productId())
                .addKeyValue("cart.sku_id", slots.skuId())
                .addKeyValue("cart.expected_price", slots.expectedPrice())
                .addKeyValue("cart.contextual_reference", slots.contextualReference())
                .addKeyValue("cart.quantity", slots.quantity())
                .addKeyValue("cart.size", cart == null ? 0 : cart.items().size())
                .log("cart manage workflow slots resolved");

        // ADD doesn't require an existing item in the cart; bypass the empty-cart short-circuit.
        if (slots.action() == CartManageAction.ADD) {
            return handleAdd(slots, cart, userId, conversationId);
        }

        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            return clarification(slots, cart, CLARIFY_EMPTY_CART, "cart_empty");
        }

        return switch (slots.action()) {
            case VIEW_CART -> handleViewCart(slots, cart);
            case REMOVE_ITEM -> handleRemoveItem(slots, cart, userId, conversationId);
            case UPDATE_QUANTITY -> handleUpdateQuantity(slots, cart, userId, conversationId);
            case CLEAR_CART -> handleClearCart(slots, cart);
            case ADD, UNKNOWN -> clarification(slots, cart, CLARIFY_UNKNOWN_ACTION, "unknown_action");
        };
    }

    // ---------- per-action handlers ----------

    private GuideNodeExecutionResult handleViewCart(CartManageSlots slots, CartView cart) {
        CartManageWorkflowResult payload = new CartManageWorkflowResult(
                CartManageAction.VIEW_CART, slots, cart, null, List.of(), List.of(), null, null,
                null, null,
                "展示购物车商品列表，包含序号、商品名、规格、数量、价格，并提示用户可执行的操作。",
                null, null
        );
        return success(payload);
    }

    private GuideNodeExecutionResult handleRemoveItem(
            CartManageSlots slots, CartView cart, String userId, String conversationId
    ) {
        ResolveOutcome resolved = resolveTargetItem(slots, cart);
        if (!resolved.resolved()) {
            return clarification(slots, cart, resolved.clarifyQuestion(), resolved.reason(), resolved.candidates());
        }
        CartItemView target = resolved.item();
        CartMutationResult mutation = cartCommandService.removeItem(userId, conversationId, String.valueOf(target.itemId()));
        if (!mutation.success()) {
            CartManageWorkflowResult payload = new CartManageWorkflowResult(
                    CartManageAction.REMOVE_ITEM, slots, cart, target, List.of(), List.of(), mutation, null,
                    null, null,
                    "告诉用户删除失败，给出失败原因。",
                    mutation.errorCode(), mutation.errorMessage()
            );
            return failed(payload, mutation.errorCode(), mutation.errorMessage());
        }
        CartManageWorkflowResult payload = new CartManageWorkflowResult(
                CartManageAction.REMOVE_ITEM, slots, cart, target, List.of(), List.of(), mutation, null,
                null, null,
                "告诉用户已删除目标商品（提供商品名），并展示删除后购物车的简要状态。",
                null, null
        );
        return success(payload);
    }

    private GuideNodeExecutionResult handleUpdateQuantity(
            CartManageSlots slots, CartView cart, String userId, String conversationId
    ) {
        Integer quantity = slots.quantity();
        if (quantity == null || quantity < 1) {
            return clarification(slots, cart, CLARIFY_QUANTITY_INVALID, "quantity_invalid");
        }
        ResolveOutcome resolved = resolveTargetItem(slots, cart);
        if (!resolved.resolved()) {
            return clarification(slots, cart, resolved.clarifyQuestion(), resolved.reason(), resolved.candidates());
        }
        CartItemView target = resolved.item();
        String productId = target.spuId() == null ? null : String.valueOf(target.spuId());
        String skuId = target.skuId() == null ? null : String.valueOf(target.skuId());
        StockResult stock = inventoryQueryService.checkStock(productId, skuId, quantity);
        if (!stock.available()) {
            String message = String.format(
                    Locale.ROOT,
                    "「%s」库存不足，当前最多可购买 %d 件。",
                    safeTitle(target),
                    Math.max(stock.availableQty(), 0)
            );
            CartManageWorkflowResult payload = new CartManageWorkflowResult(
                    CartManageAction.UPDATE_QUANTITY, slots, cart, target, List.of(), List.of(), null, stock,
                    null, null,
                    "告诉用户库存不足，给出当前可购买的数量。",
                    "INVENTORY_INSUFFICIENT", message
            );
            return failed(payload, "INVENTORY_INSUFFICIENT", message);
        }
        CartMutationResult mutation = cartCommandService.updateQuantity(
                userId, conversationId, String.valueOf(target.itemId()), quantity);
        if (!mutation.success()) {
            CartManageWorkflowResult payload = new CartManageWorkflowResult(
                    CartManageAction.UPDATE_QUANTITY, slots, cart, target, List.of(), List.of(), mutation, stock,
                    null, null,
                    "告诉用户修改数量失败，给出失败原因。",
                    mutation.errorCode(), mutation.errorMessage()
            );
            return failed(payload, mutation.errorCode(), mutation.errorMessage());
        }
        CartManageWorkflowResult payload = new CartManageWorkflowResult(
                CartManageAction.UPDATE_QUANTITY, slots, cart, target, List.of(), List.of(), mutation, stock,
                null, null,
                "告诉用户已修改目标商品数量（包含商品名和新数量），并展示购物车小计变化。",
                null, null
        );
        return success(payload);
    }

    private GuideNodeExecutionResult handleClearCart(CartManageSlots slots, CartView cart) {
        CartManageWorkflowResult payload = new CartManageWorkflowResult(
                CartManageAction.CLEAR_CART, slots, cart, null, List.of(), List.of(), null, null,
                CONFIRM_CLEAR_CART, PENDING_CONFIRM_ACTION_CLEAR_CART,
                "向用户确认是否真的要清空购物车，提示该操作不可撤销。",
                null, null
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", CartManageAction.CLEAR_CART.name());
        metadata.put("pendingConfirmAction", PENDING_CONFIRM_ACTION_CLEAR_CART);
        return new GuideNodeExecutionResult(
                NodeRunStatus.WAITING_CONFIRMATION,
                null,
                payload,
                buildStateUpdates(payload),
                Map.copyOf(metadata)
        );
    }

    private GuideNodeExecutionResult handleAdd(
            CartManageSlots slots, CartView cart, String userId, String conversationId
    ) {
        boolean hasName = StringUtils.hasText(slots.productName());
        boolean hasId = StringUtils.hasText(slots.productId()) || StringUtils.hasText(slots.skuId());
        int quantity = slots.quantity() == null || slots.quantity() < 1 ? 1 : slots.quantity();
        if (!hasName && !hasId) {
            return clarification(slots, cart, CLARIFY_ADD_NEEDS_PRODUCT_REF, "add_missing_product_ref");
        }

        ProductCandidate candidate;
        if (hasId) {
            candidate = new ProductCandidate(
                    slots.productId(),
                    slots.skuId(),
                    firstNonBlank(slots.productName(), slots.productId(), slots.skuId()),
                    null,
                    null,
                    null,
                    null
            );
        } else {
            List<ProductCandidate> candidates = productCatalogResolver.searchCandidates(slots.productName(), 5);
            if (candidates.isEmpty()) {
                return addClarification(slots, cart, CLARIFY_ADD_PRODUCT_NOT_FOUND,
                        "product_not_found", List.of());
            }
            if (candidates.size() > 1) {
                return addClarification(slots, cart, formatCandidateQuestion(candidates),
                        "need_choose_product_candidate", candidates);
            }
            candidate = candidates.getFirst();
            if (!StringUtils.hasText(candidate.productId()) || !StringUtils.hasText(candidate.skuId())) {
                return addClarification(slots, cart, formatCandidateQuestion(candidates),
                        "need_choose_product_candidate", candidates);
            }
        }

        StockResult stock = inventoryQueryService.checkStock(candidate.productId(), candidate.skuId(), quantity);
        if (!stock.available()) {
            String message = String.format(Locale.ROOT, "「%s」库存不足，当前最多可购买 %d 件。",
                    candidate.productName(), Math.max(stock.availableQty(), 0));
            CartManageWorkflowResult payload = new CartManageWorkflowResult(
                    CartManageAction.ADD, slots, cart, null, List.of(), List.of(candidate), null, stock,
                    null, null,
                    "告诉用户库存不足，给出当前可购买的数量。",
                    "INVENTORY_INSUFFICIENT", message
            );
            return failed(payload, "INVENTORY_INSUFFICIENT", message);
        }

        CartMutationResult mutation = cartCommandService.addItem(
                userId,
                conversationId,
                candidate.productId(),
                candidate.skuId(),
                quantity,
                null
        );
        if (!mutation.success()) {
            CartManageWorkflowResult payload = new CartManageWorkflowResult(
                    CartManageAction.ADD, slots, cart, null, List.of(), List.of(candidate), mutation, stock,
                    null, null,
                    "告诉用户加入购物车失败，给出失败原因。",
                    mutation.errorCode(), mutation.errorMessage()
            );
            return failed(payload, mutation.errorCode(), mutation.errorMessage());
        }
        CartManageWorkflowResult payload = new CartManageWorkflowResult(
                CartManageAction.ADD, slots, cart, null, List.of(), List.of(candidate), mutation, stock,
                null, null,
                "告诉用户已将商品加入购物车，包含商品名和数量。",
                null, null
        );
        return success(payload);
    }

    // ---------- target resolution ----------

    private ResolveOutcome resolveTargetItem(CartManageSlots slots, CartView cart) {
        List<CartItemView> items = cart.items();

        if (slots.itemIndex() != null) {
            int idx = slots.itemIndex();
            if (idx < 1 || idx > items.size()) {
                return ResolveOutcome.clarify(CLARIFY_INDEX_OUT_OF_RANGE, "index_out_of_range");
            }
            return ResolveOutcome.ok(items.get(idx - 1));
        }

        if (StringUtils.hasText(slots.skuId())) {
            List<CartItemView> matches = new ArrayList<>();
            for (CartItemView item : items) {
                if (matchesSkuId(item, slots.skuId())
                        && (!StringUtils.hasText(slots.productId()) || matchesProductId(item, slots.productId()))) {
                    matches.add(item);
                }
            }
            if (matches.size() == 1) {
                return ResolveOutcome.ok(matches.get(0));
            }
            if (matches.size() > 1) {
                return ResolveOutcome.clarify(CLARIFY_AMBIGUOUS_TARGET, "sku_ambiguous", matches);
            }
        }

        if (StringUtils.hasText(slots.productId())) {
            List<CartItemView> matches = new ArrayList<>();
            for (CartItemView item : items) {
                if (matchesProductId(item, slots.productId())) {
                    matches.add(item);
                }
            }
            if (matches.size() == 1) {
                return ResolveOutcome.ok(matches.get(0));
            }
            if (matches.size() > 1) {
                return ResolveOutcome.clarify(CLARIFY_AMBIGUOUS_TARGET, "product_ambiguous", matches);
            }
        }

        if (StringUtils.hasText(slots.productName())) {
            String needle = slots.productName().toLowerCase(Locale.ROOT);
            List<CartItemView> matches = new ArrayList<>();
            for (CartItemView item : items) {
                String title = safeTitle(item).toLowerCase(Locale.ROOT);
                if (!title.isEmpty() && title.contains(needle)) {
                    matches.add(item);
                }
            }
            if (matches.size() == 1) {
                return ResolveOutcome.ok(matches.get(0));
            }
            if (matches.size() > 1) {
                return ResolveOutcome.clarify(
                        String.format(Locale.ROOT, CLARIFY_PRODUCT_AMBIGUOUS, slots.productName()),
                        "product_ambiguous", matches);
            }
            return ResolveOutcome.clarify(
                    String.format(Locale.ROOT, CLARIFY_PRODUCT_NOT_FOUND, slots.productName()),
                    "product_not_found");
        }

        if (Boolean.TRUE.equals(slots.contextualReference()) && items.size() == 1) {
            return ResolveOutcome.ok(items.get(0));
        }

        return ResolveOutcome.clarify(CLARIFY_AMBIGUOUS_TARGET, "target_unspecified");
    }

    private boolean matchesProductId(CartItemView item, String productId) {
        if (item == null || !StringUtils.hasText(productId)) {
            return false;
        }
        String normalized = productId.trim();
        return item.spuId() != null && normalized.equals(String.valueOf(item.spuId()))
                || StringUtils.hasText(item.externalRef()) && item.externalRef().contains(normalized);
    }

    private boolean matchesSkuId(CartItemView item, String skuId) {
        if (item == null || !StringUtils.hasText(skuId)) {
            return false;
        }
        String normalized = skuId.trim();
        return item.skuId() != null && normalized.equals(String.valueOf(item.skuId()))
                || StringUtils.hasText(item.externalRef()) && item.externalRef().contains(normalized);
    }

    // ---------- slot merging ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> readIntentSlots(OverAllState state) {
        Object value = state.value(GuideGraphStateKeys.INTENT_SLOTS).orElse(null);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private CartManageSlots mergeWithIntentSlots(CartManageSlots filler, Map<String, Object> intentSlots) {
        if (filler == null) {
            filler = CartManageSlots.unknown("slot filler returned null");
        }
        if (intentSlots == null || intentSlots.isEmpty()) {
            return filler;
        }
        CartManageAction action = filler.action() == null ? CartManageAction.UNKNOWN : filler.action();
        String mainCartAction = asString(intentSlots.get(SlotKeys.CART_ACTION));
        if (StringUtils.hasText(mainCartAction)) {
            CartManageAction override = parseCartAction(mainCartAction);
            if (override != CartManageAction.UNKNOWN) {
                action = override;
            }
        }
        String productName = firstNonBlank(filler.productName(),
                asString(intentSlots.get(SlotKeys.PRODUCT_NAME)));
        String productId = firstNonBlank(filler.productId(),
                asString(intentSlots.get(SlotKeys.PRODUCT_ID)),
                asString(intentSlots.get(SlotKeys.PRODUCT_REF)));
        String skuId = firstNonBlank(filler.skuId(), asString(intentSlots.get(SlotKeys.SKU_ID)));
        Integer quantity = filler.quantity() != null ? filler.quantity()
                : asInteger(intentSlots.get(SlotKeys.QUANTITY));
        BigDecimal expectedPrice = filler.expectedPrice() != null ? filler.expectedPrice()
                : asBigDecimal(intentSlots.get(SlotKeys.EXPECTED_PRICE));
        Integer itemIndex = filler.itemIndex() != null ? filler.itemIndex()
                : asInteger(intentSlots.get(SlotKeys.ITEM_INDEX));
        Boolean contextual = filler.contextualReference();
        Object ctx = intentSlots.get(SlotKeys.CONTEXTUAL_REFERENCE);
        if (ctx instanceof Boolean b && b) {
            contextual = Boolean.TRUE;
        }
        return new CartManageSlots(action, itemIndex, productName, productId, skuId, quantity,
                contextual, expectedPrice, filler.reason());
    }

    private static CartManageAction parseCartAction(String value) {
        if (value == null) {
            return CartManageAction.UNKNOWN;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ADD", "ADD_TO_CART" -> CartManageAction.ADD;
            case "REMOVE", "REMOVE_ITEM", "REMOVE_FROM_CART" -> CartManageAction.REMOVE_ITEM;
            case "UPDATE_QUANTITY", "UPDATE", "UPDATE_CART_ITEM" -> CartManageAction.UPDATE_QUANTITY;
            case "VIEW_CART", "VIEW" -> CartManageAction.VIEW_CART;
            case "CLEAR_CART", "CLEAR" -> CartManageAction.CLEAR_CART;
            default -> CartManageAction.UNKNOWN;
        };
    }

    // ---------- result builders ----------

    private GuideNodeExecutionResult success(CartManageWorkflowResult payload) {
        return new GuideNodeExecutionResult(
                NodeRunStatus.SUCCESS,
                null,
                payload,
                buildStateUpdates(payload),
                buildMetadata(payload, null, null)
        );
    }

    private GuideNodeExecutionResult failed(CartManageWorkflowResult payload, String errorCode, String errorMessage) {
        return new GuideNodeExecutionResult(
                NodeRunStatus.FAILED,
                null,
                payload,
                buildStateUpdates(payload),
                buildMetadata(payload, errorCode, errorMessage)
        );
    }

    private GuideNodeExecutionResult clarification(
            CartManageSlots slots, CartView cart, String question, String reason
    ) {
        return clarification(slots, cart, question, reason, List.of());
    }

    private GuideNodeExecutionResult clarification(
            CartManageSlots slots, CartView cart, String question, String reason, List<CartItemView> candidates
    ) {
        CartManageAction action = slots == null || slots.action() == null
                ? CartManageAction.UNKNOWN
                : slots.action();
        CartManageWorkflowResult payload = new CartManageWorkflowResult(
                action, slots, cart, null, candidates, List.of(), null, null,
                question, null,
                "向用户发起澄清，问题已附在 clarifyQuestion 字段。",
                null, null
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", action.name());
        putIfNotBlank(metadata, "clarifyReason", reason);
        Map<String, Object> updates = new LinkedHashMap<>(buildStateUpdates(payload));
        putIfNotBlank(updates, GuideGraphStateKeys.CLARIFY_REASON, reason);
        return new GuideNodeExecutionResult(
                NodeRunStatus.WAITING_CLARIFICATION,
                null,
                payload,
                Map.copyOf(updates),
                Map.copyOf(metadata)
        );
    }

    private GuideNodeExecutionResult addClarification(
            CartManageSlots slots,
            CartView cart,
            String question,
            String reason,
            List<ProductCandidate> candidates
    ) {
        CartManageWorkflowResult payload = new CartManageWorkflowResult(
                CartManageAction.ADD, slots, cart, null, List.of(), candidates, null, null,
                question, null,
                "向用户发起加购澄清，问题已附在 clarifyQuestion 字段。",
                null, null
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", CartManageAction.ADD.name());
        putIfNotBlank(metadata, "clarifyReason", reason);
        if (candidates != null && !candidates.isEmpty()) {
            metadata.put("productCandidates", candidates);
        }
        Map<String, Object> updates = new LinkedHashMap<>(buildStateUpdates(payload));
        putIfNotBlank(updates, GuideGraphStateKeys.CLARIFY_REASON, reason);
        return new GuideNodeExecutionResult(
                NodeRunStatus.WAITING_CLARIFICATION,
                null,
                payload,
                Map.copyOf(updates),
                Map.copyOf(metadata)
        );
    }

    private Map<String, Object> buildStateUpdates(CartManageWorkflowResult payload) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(GuideGraphStateKeys.WORKFLOW_RESULT, payload);
        updates.put(GuideGraphStateKeys.WORKFLOW_STATUS, payloadStatus(payload));
        updates.put(GuideGraphStateKeys.CART_ACTION,
                payload.action() == null ? "UNKNOWN" : payload.action().name());
        if (payload.slots() != null) {
            putIfNotBlank(updates, GuideGraphStateKeys.PRODUCT_NAME, payload.slots().productName());
        }
        putIfNotBlank(updates, GuideGraphStateKeys.NODE_MESSAGE, payload.clarifyQuestion());
        if (payload.clarifyQuestion() != null) {
            updates.put(GuideGraphStateKeys.NEED_USER_INPUT, true);
        }
        if (!payload.productCandidates().isEmpty()) {
            updates.put(GuideGraphStateKeys.PRODUCT_CANDIDATES, payload.productCandidates());
        }

        // Map.of() rejects null values, which makes it brittle for clarification / partial paths
        // where most fields legitimately have no data yet. Use LinkedHashMap with put-if-not-null
        // helpers so a clarify on missing slots never NPEs.
        Map<String, Object> evidence = new LinkedHashMap<>();
        putIfNotNull(evidence, "cartBefore", payload.cartBefore());
        putIfNotNull(evidence, "slots", payload.slots());
        putIfNotNull(evidence, "targetItem", payload.targetItem());
        evidence.put("candidateItems", payload.candidateItems());        // record canonicalises to []
        evidence.put("productCandidates", payload.productCandidates());
        updates.put(GuideGraphStateKeys.EVIDENCE, Map.copyOf(evidence));

        Map<String, Object> business = new LinkedHashMap<>();
        business.put("action", payload.action() == null ? "UNKNOWN" : payload.action().name());
        putIfNotNull(business, "mutationResult", payload.mutationResult());
        putIfNotNull(business, "stockResult", payload.stockResult());
        putIfNotBlank(business, "pendingConfirmAction", payload.pendingConfirmAction());
        putIfNotBlank(business, "errorCode", payload.errorCode());
        putIfNotBlank(business, "errorMessage", payload.errorMessage());
        putIfNotBlank(business, "nodeMessage", payload.clarifyQuestion());
        updates.put(GuideGraphStateKeys.BUSINESS_RESULT, Map.copyOf(business));
        return updates;
    }

    private String payloadStatus(CartManageWorkflowResult payload) {
        if (payload.errorCode() != null) {
            return NodeRunStatus.FAILED.name();
        }
        if (payload.pendingConfirmAction() != null) {
            return NodeRunStatus.WAITING_CONFIRMATION.name();
        }
        if (payload.clarifyQuestion() != null) {
            return NodeRunStatus.WAITING_CLARIFICATION.name();
        }
        return NodeRunStatus.SUCCESS.name();
    }

    private String formatCandidateQuestion(List<ProductCandidate> candidates) {
        StringBuilder builder = new StringBuilder(CLARIFY_ADD_CHOOSE_CANDIDATE);
        for (int i = 0; i < candidates.size(); i++) {
            ProductCandidate candidate = candidates.get(i);
            builder.append('\n')
                    .append(i + 1)
                    .append(". ")
                    .append(candidate.productName() == null ? "未命名商品" : candidate.productName());
            if (candidate.price() != null) {
                builder.append(" - ¥").append(candidate.price());
            }
            if (StringUtils.hasText(candidate.spec())) {
                builder.append(" - ").append(candidate.spec());
            } else if (StringUtils.hasText(candidate.brief())) {
                builder.append(" - ").append(candidate.brief());
            }
        }
        builder.append("\n请回复“选第 1 个”或对应序号。");
        return builder.toString();
    }

    private Map<String, Object> buildMetadata(
            CartManageWorkflowResult payload, String errorCode, String errorMessage
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", payload.action() == null ? "UNKNOWN" : payload.action().name());
        int itemCount = 0;
        if (payload.cartBefore() != null && payload.cartBefore().items() != null) {
            itemCount = payload.cartBefore().items().size();
        }
        metadata.put("cartItemCount", itemCount);
        putIfNotBlank(metadata, "errorCode", errorCode);
        putIfNotBlank(metadata, "errorMessage", errorMessage);
        return Map.copyOf(metadata);
    }

    // ---------- helpers ----------

    private String renderMemory(OverAllState state) {
        return GuideGraphContextSupport.conversationMemoryFromState(state);
    }

    private String requiredString(OverAllState state, String key) {
        Object value = state.value(key).orElse(null);
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            throw new IllegalStateException("cart_manage_workflow missing required state key: " + key);
        }
        return String.valueOf(value);
    }

    private String safeTitle(CartItemView item) {
        return item == null || item.title() == null ? "" : item.title();
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    /**
     * Internal record for cart-item resolution outcome.
     */
    private record ResolveOutcome(
            boolean resolved,
            CartItemView item,
            String clarifyQuestion,
            String reason,
            List<CartItemView> candidates
    ) {
        static ResolveOutcome ok(CartItemView item) {
            return new ResolveOutcome(true, item, null, null, List.of());
        }

        static ResolveOutcome clarify(String question, String reason) {
            return new ResolveOutcome(false, null, question, reason, List.of());
        }

        static ResolveOutcome clarify(String question, String reason, List<CartItemView> candidates) {
            return new ResolveOutcome(false, null, question, reason, List.copyOf(candidates));
        }
    }
}
