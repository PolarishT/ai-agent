package com.bytedance.ai.graph.cart.persistence.jdbc;

import com.bytedance.ai.graph.cart.persistence.ShoppingCartRecord;
import com.bytedance.ai.graph.cart.persistence.ShoppingCartRepository;
import com.bytedance.ai.graph.cart.api.CartState;
import com.bytedance.ai.graph.cart.workflow.CartConcurrencyConflictException;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 购物车仓储的 JDBC 实现。
 */
@Repository
public class JdbcShoppingCartRepository implements ShoppingCartRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;
    /** 数据库类型运行期不变，首次探测后缓存：PG 走抢占式 ON CONFLICT，其它库走兼容路径。 */
    private volatile Boolean postgres;

    public JdbcShoppingCartRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShoppingCartRecord create(String userId, String conversationId) {
        if (isPostgres()) {
            return createPreemptive(userId, conversationId);
        }
        return createPlain(userId, conversationId);
    }

    /**
     * 抢占式创建：并发首次请求只有一个事务插入成功；其余命中部分唯一索引
     * uq_shopping_cart_active_owner，被 {@code ON CONFLICT DO NOTHING} 静默忽略
     * （不报错、不污染事务），随后读回既有活跃车。彻底消除「先查再建」产生的重复活跃购物车。
     */
    private ShoppingCartRecord createPreemptive(String userId, String conversationId) {
        String cartId = "cart_" + UUID.randomUUID().toString().replace("-", "");
        List<ShoppingCartRecord> inserted = jdbc.query(
                """
                INSERT INTO shopping_cart (cart_id, user_id, conversation_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, conversation_id) WHERE state NOT IN ('PLACED', 'CANCELLED')
                DO NOTHING
                RETURNING *
                """,
                rowMapper(),
                cartId,
                userId,
                conversationId
        );
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        // 命中冲突说明已有活跃车（并发胜出方已提交）：读回它，保证 findOrCreate 对并发双方返回同一车。
        return findLatestActive(userId, conversationId)
                .orElseThrow(() -> new IllegalStateException(
                        "并发创建后未找到活跃购物车: user=" + userId + ", conversation=" + conversationId));
    }

    /**
     * 非 PostgreSQL（如测试用 H2）退化为普通插入：这些环境为单线程集成测试，不存在并发竞争。
     * INSERT ... RETURNING * 一次往返完成落库并读回（H2 PostgreSQL 模式同样支持）。
     */
    private ShoppingCartRecord createPlain(String userId, String conversationId) {
        String cartId = "cart_" + UUID.randomUUID().toString().replace("-", "");
        ShoppingCartRecord created = jdbc.queryForObject(
                """
                INSERT INTO shopping_cart (cart_id, user_id, conversation_id)
                VALUES (?, ?, ?)
                RETURNING *
                """,
                rowMapper(),
                cartId,
                userId,
                conversationId
        );
        if (created == null) {
            throw new IllegalStateException("创建购物车失败，未返回数据");
        }
        return created;
    }

    private boolean isPostgres() {
        Boolean cached = postgres;
        if (cached == null) {
            cached = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) this::isPostgreSql));
            postgres = cached;
        }
        return cached;
    }

    @Override
    public Optional<ShoppingCartRecord> findLatestActive(String userId, String conversationId) {
        return jdbc.query(
                """
                SELECT * FROM shopping_cart
                 WHERE user_id = ?
                   AND conversation_id = ?
                   AND state NOT IN ('PLACED', 'CANCELLED')
                 ORDER BY updated_at DESC, id DESC
                 LIMIT 1
                """,
                rowMapper(),
                userId,
                conversationId
        ).stream().findFirst();
    }

    @Override
    public Optional<ShoppingCartRecord> findLatestActiveWithItemsByUser(String userId) {
        return jdbc.query(
                """
                SELECT c.*
                  FROM shopping_cart c
                 WHERE c.user_id = ?
                   AND c.state NOT IN ('PLACED', 'CANCELLED')
                   AND EXISTS (
                       SELECT 1
                         FROM cart_item i
                        WHERE i.cart_id = c.id
                          AND i.status = 'ACTIVE'
                   )
                 ORDER BY c.updated_at DESC, c.id DESC
                 LIMIT 1
                """,
                rowMapper(),
                userId
        ).stream().findFirst();
    }

    @Override
    public Optional<ShoppingCartRecord> findById(Long id) {
        return jdbc.query("SELECT * FROM shopping_cart WHERE id = ?", rowMapper(), id).stream().findFirst();
    }

    @Override
    public void updateState(Long id, long expectedVersion, CartState state) {
        int updated = jdbc.update(
                "UPDATE shopping_cart SET state = ?, version = version + 1, updated_at = now() WHERE id = ? AND version = ?",
                state.name(),
                id,
                expectedVersion
        );
        if (updated == 0) {
            throw new CartConcurrencyConflictException(
                    "购物车已被其它操作修改（乐观锁冲突），请重试：cartRowId=" + id + ", expectedVersion=" + expectedVersion);
        }
    }

    // 不自增 version：版本号仅由 updateState 推进。本方法总是发生在同事务的 updateState 之后，
    // 受其已取得的行写锁保护，无需再做乐观锁校验，也不能再次 +1（否则会打乱后续状态跃迁的版本预期）。
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTotals(Long id, BigDecimal subtotalAmount, int itemCount) {
        jdbc.update(
                """
                UPDATE shopping_cart
                   SET subtotal_amount = ?,
                       item_count = ?,
                       updated_at = now()
                 WHERE id = ?
                """,
                subtotalAmount == null ? BigDecimal.ZERO : subtotalAmount,
                itemCount,
                id
        );
    }

    // 同 updateTotals：不自增 version，依赖前序 updateState 的行锁与版本推进。
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShippingAddress(Long id, Map<String, Object> shippingAddress) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(updateAddressSql(connection));
            statement.setString(1, jsonCodec.write(shippingAddress == null ? Map.of() : shippingAddress));
            statement.setLong(2, id);
            return statement;
        });
    }

    private String updateAddressSql(Connection connection) throws SQLException {
        if (isPostgreSql(connection)) {
            return """
                    UPDATE shopping_cart
                       SET shipping_address_json = CAST(? AS jsonb),
                           updated_at = now()
                     WHERE id = ?
                    """;
        }
        return """
                UPDATE shopping_cart
                   SET shipping_address_json = ?,
                       updated_at = now()
                 WHERE id = ?
                """;
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private RowMapper<ShoppingCartRecord> rowMapper() {
        return (rs, _) -> new ShoppingCartRecord(
                rs.getLong("id"),
                rs.getString("cart_id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                CartState.valueOf(rs.getString("state")),
                rs.getString("currency"),
                rs.getBigDecimal("subtotal_amount"),
                rs.getInt("item_count"),
                jsonCodec.readMap(rs.getString("shipping_address_json")),
                rs.getLong("version"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
