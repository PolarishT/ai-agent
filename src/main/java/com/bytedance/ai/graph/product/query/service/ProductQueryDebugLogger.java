package com.bytedance.ai.graph.product.query.service;

import com.bytedance.ai.graph.product.query.AttributeIncludeExclude;
import com.bytedance.ai.graph.product.query.ProductAttributesCondition;
import com.bytedance.ai.graph.product.query.ProductHydrationOptions;
import com.bytedance.ai.graph.product.query.ProductQueryCondition;
import com.bytedance.ai.graph.product.query.ProductQueryIntent;
import com.bytedance.ai.graph.product.retrieval.filter.MilvusScalarFilterBuilder;
import com.bytedance.ai.graph.product.retrieval.filter.PostgresFilterFragment;
import com.bytedance.ai.graph.product.retrieval.filter.ProductPostgresFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * product_query_workflow 调试日志器：把链路里关键的 slot / PG hard-filter / Milvus filter /
 * post-filter 生效情况打成统一前缀的日志，方便临时观测。
 *
 * <p>所有日志都以 {@value #PREFIX} 开头，查看时直接：
 * <pre>{@code  grep -- '--> ---> 测试' app.log }</pre>
 *
 * <p>纯观测组件：不改变任何业务结果；filter 的 build 仅为打印预览，开销是字符串拼接级别。
 */
@Component
public class ProductQueryDebugLogger {

    static final String PREFIX = "--> ---> 测试";

    private static final Logger log = LoggerFactory.getLogger(ProductQueryDebugLogger.class);

    private final ProductPostgresFilterBuilder postgresFilterBuilder;
    private final MilvusScalarFilterBuilder milvusScalarFilterBuilder;

    public ProductQueryDebugLogger(
            ProductPostgresFilterBuilder postgresFilterBuilder,
            MilvusScalarFilterBuilder milvusScalarFilterBuilder
    ) {
        this.postgresFilterBuilder = postgresFilterBuilder;
        this.milvusScalarFilterBuilder = milvusScalarFilterBuilder;
    }

    /**
     * 打印解析 / 合并 / 校验后的 slot 信息（最终用于检索的版本）。
     */
    public void logSlots(ProductQueryCondition condition) {
        if (!log.isInfoEnabled() || condition == null) {
            return;
        }
        log.info("{} [SLOT] raw='{}' normalized='{}' kw='{}' sem='{}'",
                PREFIX, condition.rawQuery(), condition.normalizedQuery(),
                condition.keywordQuery(), condition.semanticQuery());
        log.info("{} [SLOT] intent={} mode={} refine={} sort={} confidence={} needClarify={} missing={}",
                PREFIX, condition.intent(), condition.queryMode(), condition.refineType(),
                condition.sort(), condition.confidence(), condition.needClarify(), condition.missingSlots());
        log.info("{} [SLOT] category(in)={} category(ex)={} brand(in)={} brand(ex)={}",
                PREFIX, condition.categoryTerms(), condition.excludeCategoryTerms(),
                condition.brandTerms(), condition.excludeBrandTerms());
        log.info("{} [SLOT] include={} exclude={}",
                PREFIX, condition.includeTerms(), condition.excludeTerms());
        log.info("{} [SLOT] price=[{},{}] mustHaveStock={} needComparison={} comparisonTargets={}",
                PREFIX, condition.priceMin(), condition.priceMax(), condition.mustHaveStock(),
                condition.needComparison(), condition.comparisonTargets());
        log.info("{} [SLOT] {}", PREFIX, formatAttributes(condition.attributes()));
    }

    /**
     * 旧 1 参数签名：未知 intent 时按 {@link ProductQueryIntent#PRODUCT_SEARCH} 打 milvus filter。
     */
    public void logFilters(ProductQueryCondition condition) {
        logFilters(condition, ProductQueryIntent.PRODUCT_SEARCH);
    }

    /**
     * 打印 PG hard-filter 与 Milvus scalar filter（PREFERRED + FALLBACK 两条）。
     *
     * <p>keyword 路径已不再 JOIN {@code rag_chunks}，PG hard-filter 对 keyword / semantic
     * 两路输出一致，因此这里只打一条 [PG-FILTER]。Milvus 现在按 intent 区分 PREFERRED / FALLBACK
     * 两套 chunkType filter，由 {@code ProductSemanticRagRetriever} 决定先用哪个、何时切换。
     */
    public void logFilters(ProductQueryCondition condition, ProductQueryIntent intent) {
        if (!log.isInfoEnabled() || condition == null) {
            return;
        }
        PostgresFilterFragment fragment = postgresFilterBuilder.build(condition);
        log.info("{} [PG-FILTER] effective={} sql=[{}] params={}",
                PREFIX, !fragment.isEmpty(), flatten(fragment.sql()), fragment.params());

        ProductQueryIntent resolved = intent == null ? ProductQueryIntent.PRODUCT_SEARCH : intent;
        String preferredExpr = milvusScalarFilterBuilder.buildPreferred(resolved);
        String fallbackExpr = milvusScalarFilterBuilder.buildFallback(resolved);
        log.info("{} [MILVUS-FILTER] stage=PREFERRED intent={} effective={} expr=[{}]",
                PREFIX, resolved, preferredExpr != null && !preferredExpr.isBlank(),
                preferredExpr == null ? "" : preferredExpr);
        log.info("{} [MILVUS-FILTER] stage=FALLBACK  intent={} effective={} expr=[{}] same_as_preferred={}",
                PREFIX, resolved, fallbackExpr != null && !fallbackExpr.isBlank(),
                fallbackExpr == null ? "" : fallbackExpr,
                fallbackExpr != null && fallbackExpr.equals(preferredExpr));
    }

    /**
     * 打印按意图决定的 hydrate 范围。
     */
    public void logIntent(ProductQueryIntent intent, ProductHydrationOptions options) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("{} [INTENT] productQueryIntent={} hydration={}", PREFIX, intent, options);
    }

    /**
     * 打印 hydrate 后 post-filter 的生效情况。
     *
     * @param hydratedCount post-filter 之前的候选数（已 hydrate）
     * @param result        post-filter 结果
     */
    public void logPostFilter(int hydratedCount, ProductCandidatePostFilter.Result result) {
        if (!log.isInfoEnabled() || result == null) {
            return;
        }
        boolean effective = !result.removed().isEmpty();
        log.info("{} [POST-FILTER] effective={} hydrated={} removed={} remaining={} reasons={}",
                PREFIX, effective, hydratedCount,
                result.removed().size(), result.kept().size(), result.removalReasons());
    }

    private String formatAttributes(ProductAttributesCondition attributes) {
        if (attributes == null) {
            return "attr=<none>";
        }
        return "attr.color(in/ex)=" + formatPair(attributes.color())
                + " size(in/ex)=" + formatPair(attributes.size())
                + " material(in/ex)=" + formatPair(attributes.material())
                + " capacity=" + attributes.capacity();
    }

    private String formatPair(AttributeIncludeExclude pair) {
        if (pair == null) {
            return "[]/[]";
        }
        return pair.include() + "/" + pair.exclude();
    }

    private String flatten(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return sql.replaceAll("\\s+", " ").trim();
    }
}
