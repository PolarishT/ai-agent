package com.bytedance.ai.graph.ordermanage;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.catalog.api.CatalogInventoryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.orchestration.GuideGraphStateKeys;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.CartMutationResult;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRepository;
import com.bytedance.ai.graph.ordermanage.application.OrderAddressResolver;
import com.bytedance.ai.graph.ordermanage.application.OrderCartSnapshotService;
import com.bytedance.ai.graph.ordermanage.application.OrderCommandService;
import com.bytedance.ai.graph.ordermanage.persistence.MockOrderRecord;
import com.bytedance.ai.graph.ordermanage.persistence.MockOrderRepository;
import com.bytedance.ai.graph.ordermanage.persistence.PendingOrderActionRecord;
import com.bytedance.ai.graph.ordermanage.persistence.PendingOrderActionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class OrderManageSubgraphFactoryTest {

    private static final String USER_ID = "user-1";
    private static final String CONVERSATION_ID = "conversation-1";

    @Test
    void emptyCartCheckoutReturnsEmptyMessage() throws Exception {
        TestRig rig = new TestRig(cart());

        OverAllState state = rig.invoke("结算购物车");

        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .isEqualTo("你的购物车目前是空的，无法下单。");
        assertThat(rig.pending.active).isEmpty();
    }

    @Test
    void checkoutCreatesWaitingAddressPendingWithoutOrder() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 2)));

        OverAllState state = rig.invoke("结算购物车");

        assertThat(rig.pending.active).isPresent();
        assertThat(rig.pending.active.get().status()).isEqualTo(OrderManageStatus.WAITING_ADDRESS);
        assertThat(rig.mockOrders.created).isZero();
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("请补充");
    }

    @Test
    void waitingAddressMissingPhoneAsksForPhone() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));
        rig.pending.active = Optional.of(rig.pendingRecord(OrderManageStatus.WAITING_ADDRESS, Map.of()));

        OverAllState state = rig.invoke("地址：UNSW High Street, Kensington NSW 2052，联系人 Zhang");

        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("请补充联系电话");
        assertThat(rig.mockOrders.created).isZero();
    }

    @Test
    void fullAddressBuildsSummaryWithoutCreatingOrder() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));
        rig.pending.active = Optional.of(rig.pendingRecord(OrderManageStatus.WAITING_ADDRESS, Map.of()));

        OverAllState state = rig.invoke("地址：UNSW High Street, Kensington NSW 2052，联系人 Zhang，电话 0412345678");

        assertThat(rig.pending.active.get().status()).isEqualTo(OrderManageStatus.WAITING_CONFIRMATION);
        assertThat(rig.inventory.deductCalls).isZero();
        assertThat(rig.mockOrders.created).isZero();
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("请确认订单信息")
                .contains("确认下单请回复");
    }

    @Test
    void confirmCreatesMockOrderClearsCartAndMarksCreated() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));
        rig.pending.active = Optional.of(rig.pendingRecord(
                OrderManageStatus.WAITING_CONFIRMATION,
                new AddressSnapshot("Zhang", "0412345678", "UNSW High Street", "2052", "Sydney", "NSW").toMap()
        ));

        OverAllState state = rig.invoke("确认下单");

        assertThat(rig.pending.createdOrderNo).startsWith("ORD-");
        assertThat(rig.inventory.deductCalls).isEqualTo(1);
        assertThat(rig.mockOrders.created).isEqualTo(1);
        assertThat(rig.cartCommand.clearCalled).isTrue();
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("订单已提交成功")
                .contains("当前为模拟下单，未进行真实支付");
    }

    @Test
    void cancelDoesNotDeductOrCreateOrder() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));
        rig.pending.active = Optional.of(rig.pendingRecord(OrderManageStatus.WAITING_CONFIRMATION, Map.of()));

        OverAllState state = rig.invoke("取消");

        assertThat(rig.pending.cancelled).isTrue();
        assertThat(rig.inventory.deductCalls).isZero();
        assertThat(rig.mockOrders.created).isZero();
        assertThat(state.value(OrderManageStateKeys.ORDER_STATUS, ""))
                .isEqualTo(OrderManageStatus.CANCELLED.name());
    }

    @Test
    void confirmWithoutPendingCannotCreateOrder() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));

        OverAllState state = rig.invoke("确认下单");

        assertThat(rig.mockOrders.created).isZero();
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("当前没有待确认的订单");
    }

    @Test
    void waitingAddressConfirmRequiresAddressFirst() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));
        rig.pending.active = Optional.of(rig.pendingRecord(OrderManageStatus.WAITING_ADDRESS, Map.of()));

        OverAllState state = rig.invoke("确认");

        assertThat(rig.mockOrders.created).isZero();
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("请先提供收货地址");
    }

    @Test
    void repeatedConfirmCannotCreateSecondOrder() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));
        rig.pending.active = Optional.of(rig.pendingRecord(
                OrderManageStatus.WAITING_CONFIRMATION,
                new AddressSnapshot("Zhang", "0412345678", "UNSW High Street", null, null, null).toMap()
        ));

        rig.invoke("确认下单");
        OverAllState second = rig.invoke("确认下单");

        assertThat(rig.mockOrders.created).isEqualTo(1);
        assertThat(second.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("当前没有待确认的订单");
    }

    @Test
    void confirmWhenCartChangedFailsBeforeDeductingStock() throws Exception {
        TestRig rig = new TestRig(cart(item(1L, 101L, "苹果", 1)));
        rig.pending.active = Optional.of(rig.pendingRecord(
                OrderManageStatus.WAITING_CONFIRMATION,
                new AddressSnapshot("Zhang", "0412345678", "UNSW High Street", null, null, null).toMap()
        ));
        rig.cart = cart(item(1L, 101L, "苹果", 2));

        OverAllState state = rig.invoke("确认下单");

        assertThat(rig.inventory.deductCalls).isZero();
        assertThat(rig.mockOrders.created).isZero();
        assertThat(state.value(OrderManageStateKeys.NODE_MESSAGE, ""))
                .contains("购物车内容已变化");
    }

    private static CartItemView item(Long itemId, Long spuId, String title, int quantity) {
        BigDecimal unitPrice = new BigDecimal("9.90");
        return new CartItemView(itemId, spuId, "SPU-" + spuId, title, "brand", null, quantity,
                unitPrice, unitPrice.multiply(BigDecimal.valueOf(quantity)), 10);
    }

    private static CartView cart(CartItemView... items) {
        List<CartItemView> itemList = List.of(items);
        BigDecimal amount = itemList.stream()
                .map(CartItemView::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int itemCount = itemList.stream().mapToInt(item -> item.quantity() == null ? 0 : item.quantity()).sum();
        return new CartView("cart-1", USER_ID, CONVERSATION_ID, CartState.IN_CART, "CNY",
                amount, itemCount, Map.of(), itemList);
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static final class TestRig {
        CartView cart;
        final StubPendingOrderActionRepository pending = new StubPendingOrderActionRepository();
        final StubCatalog catalog = new StubCatalog();
        final StubInventory inventory = new StubInventory();
        final StubCartCommand cartCommand = new StubCartCommand();
        final StubMockOrders mockOrders = new StubMockOrders();
        final OrderCartSnapshotService snapshots = new OrderCartSnapshotService();
        final OrderManageSubgraphFactory factory;

        TestRig(CartView cart) {
            this.cart = cart;
            OrderCommandService commandService = new OrderCommandService(
                    pending,
                    (userId, conversationId) -> this.cart,
                    catalog,
                    inventory,
                    cartCommand,
                    mockOrders,
                    snapshots
            );
            this.factory = new OrderManageSubgraphFactory(
                    (userId, conversationId) -> this.cart,
                    catalog,
                    pending,
                    new OrderAddressResolver(),
                    snapshots,
                    commandService,
                    provider((PendingCartActionRepository) null)
            );
        }

        OverAllState invoke(String message) throws Exception {
            Map<String, Object> initialState = new LinkedHashMap<>();
            initialState.put(GuideGraphStateKeys.USER_ID, USER_ID);
            initialState.put(GuideGraphStateKeys.CONVERSATION_ID, CONVERSATION_ID);
            initialState.put(GuideGraphStateKeys.MESSAGE, message);
            return factory.build().compile().invoke(initialState).orElseThrow();
        }

        PendingOrderActionRecord pendingRecord(OrderManageStatus status, Map<String, Object> address) {
            Map<String, Object> snapshot = snapshots.snapshot(cart);
            return new PendingOrderActionRecord(
                    1L,
                    USER_ID,
                    CONVERSATION_ID,
                    snapshot,
                    snapshots.hash(snapshot),
                    address,
                    snapshots.amount(cart),
                    status,
                    null,
                    null,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    LocalDateTime.now().plusMinutes(30)
            );
        }
    }

    private static final class StubPendingOrderActionRepository implements PendingOrderActionRepository {
        Optional<PendingOrderActionRecord> active = Optional.empty();
        long nextId = 1L;
        boolean cancelled;
        String createdOrderNo;

        @Override
        public Optional<PendingOrderActionRecord> findActiveByUserIdAndConversationId(String userId, String conversationId) {
            return active.filter(record -> record.status() == OrderManageStatus.WAITING_ADDRESS
                    || record.status() == OrderManageStatus.WAITING_CONFIRMATION
                    || record.status() == OrderManageStatus.CREATING);
        }

        @Override
        public Optional<PendingOrderActionRecord> findById(Long id) {
            return active.filter(record -> record.id().equals(id));
        }

        @Override
        public PendingOrderActionRecord save(PendingOrderActionRecord record) {
            PendingOrderActionRecord saved = copy(record, nextId++, record.status(), record.addressSnapshot(),
                    record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null);
            active = Optional.of(saved);
            return saved;
        }

        @Override
        public void updateAddress(Long id, Map<String, Object> addressSnapshot) {
            active = active.map(record -> copy(record, record.id(), record.status(), addressSnapshot,
                    record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null));
        }

        @Override
        public void markWaitingAddress(Long id) {
            active = active.map(record -> copy(record, record.id(), OrderManageStatus.WAITING_ADDRESS,
                    record.addressSnapshot(), record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null));
        }

        @Override
        public void markWaitingConfirmation(Long id, Map<String, Object> cartSnapshot, String cartSnapshotHash,
                                            Map<String, Object> addressSnapshot, BigDecimal amount) {
            active = active.map(record -> copy(record, record.id(), OrderManageStatus.WAITING_CONFIRMATION,
                    addressSnapshot, cartSnapshot, cartSnapshotHash, amount, null));
        }

        @Override
        public boolean markCreatingIfWaitingConfirmation(Long id) {
            if (active.isEmpty() || active.get().status() != OrderManageStatus.WAITING_CONFIRMATION) {
                return false;
            }
            active = active.map(record -> copy(record, record.id(), OrderManageStatus.CREATING,
                    record.addressSnapshot(), record.cartSnapshot(), record.cartSnapshotHash(), record.amountSnapshot(), null));
            return true;
        }

        @Override
        public void markCreated(Long id, String orderNo) {
            createdOrderNo = orderNo;
            active = Optional.empty();
        }

        @Override
        public void markCancelled(Long id) {
            cancelled = true;
            active = Optional.empty();
        }

        @Override
        public void markFailed(Long id, String reason) {
            active = Optional.empty();
        }

        @Override
        public void markExpired(Long id) {
            active = Optional.empty();
        }

        private PendingOrderActionRecord copy(PendingOrderActionRecord record, Long id, OrderManageStatus status,
                                              Map<String, Object> address, Map<String, Object> cartSnapshot,
                                              String cartSnapshotHash, BigDecimal amount, String orderNo) {
            return new PendingOrderActionRecord(id, record.userId(), record.conversationId(), cartSnapshot,
                    cartSnapshotHash, address, amount, status, record.failReason(), orderNo,
                    record.createdAt(), LocalDateTime.now(), record.expireAt());
        }
    }

    private static final class StubCatalog implements CatalogQueryFacade {
        int stock = 10;

        @Override
        public CatalogProductView getProduct(Long productId) {
            return new CatalogProductView(productId, "苹果", "brand", "fruit", null,
                    new BigDecimal("9.90"), new BigDecimal("9.90"), new BigDecimal("9.90"), stock, "", "ACTIVE",
                    Map.of(), Map.of(), List.of(), OffsetDateTime.now(), OffsetDateTime.now());
        }

        @Override
        public List<CatalogSkuView> listSkus(Long productId) {
            return List.of();
        }
    }

    private static final class StubInventory implements CatalogInventoryFacade {
        int deductCalls;

        @Override
        public void decreaseStock(Long spuId, int quantity) {
            deductCalls++;
        }
    }

    private static final class StubCartCommand implements CartCommandService {
        boolean clearCalled;

        @Override
        public CartMutationResult addItem(String userId, String conversationId, String productId, String skuId,
                                          int quantity, BigDecimal expectedUnitPrice) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult removeItem(String userId, String conversationId, String cartItemId) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult updateQuantity(String userId, String conversationId, String cartItemId, int quantity) {
            return CartMutationResult.ok(null);
        }

        @Override
        public CartMutationResult clearCart(String userId, String conversationId) {
            clearCalled = true;
            return CartMutationResult.ok(null);
        }
    }

    private static final class StubMockOrders implements MockOrderRepository {
        int created;

        @Override
        public MockOrderRecord create(String orderNo, String userId, String conversationId, Map<String, Object> items,
                                      Map<String, Object> address, BigDecimal totalAmount) {
            created++;
            return new MockOrderRecord(1L, orderNo, userId, conversationId, items, address, totalAmount,
                    "CREATED", LocalDateTime.now());
        }
    }
}
