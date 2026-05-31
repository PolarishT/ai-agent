package com.bytedance.ai.graph.catalog.application;

import com.bytedance.ai.graph.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import com.bytedance.ai.graph.catalog.api.CatalogProductCreateRequest;
import com.bytedance.ai.graph.catalog.api.CatalogProductJsonImportRequest;
import com.bytedance.ai.graph.catalog.persistence.CatalogAttributeOutboxRepository;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRecord;
import com.bytedance.ai.graph.catalog.persistence.CatalogProductRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Catalog 写入侧 Facade 实现。
 *
 * <p>本服务只负责编排：循环遍历批量请求，委托 {@link CatalogImportService} 走单条事务。
 * 单条失败的异常会被捕获并归集到 {@link CatalogImportSummary#failures()}，
 * 不会影响其它条目；这种设计配合 REQUIRES_NEW 让导入具备「部分成功」语义。
 */
@Service
class CatalogCommandService implements CatalogCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(CatalogCommandService.class);
    private static final String MANUAL_TRIGGER = "manual-retry";

    private final CatalogImportService catalogImportService;
    private final CatalogProductRepository productRepository;
    private final CatalogAttributeOutboxRepository attributeOutboxRepository;
    private final RagJsonCodec jsonCodec;
    private final CatalogJsonImportMapper jsonImportMapper;

    CatalogCommandService(
            CatalogImportService catalogImportService,
            CatalogProductRepository productRepository,
            CatalogAttributeOutboxRepository attributeOutboxRepository,
            RagJsonCodec jsonCodec,
            CatalogJsonImportMapper jsonImportMapper
    ) {
        this.catalogImportService = catalogImportService;
        this.productRepository = productRepository;
        this.attributeOutboxRepository = attributeOutboxRepository;
        this.jsonCodec = jsonCodec;
        this.jsonImportMapper = jsonImportMapper;
    }

    @Override
    public CatalogImportSummary importBatch(CatalogImportRequest request) {
        List<?> items = request.items();
        List<Long> succeededIds = new ArrayList<>(items.size());
        List<CatalogImportSummary.Failure> failures = new ArrayList<>();

        for (Object item : items) {
            try {
                Long productId = importOne(item);
                succeededIds.add(productId);
            } catch (RuntimeException exception) {
                log.warn(
                        "catalog import single Product failed: title={}, reason={}",
                        itemTitle(item),
                        exception.getMessage()
                );
                failures.add(new CatalogImportSummary.Failure(itemTitle(item), exception.getMessage()));
            }
        }
        log.info(
                "catalog import batch completed: total={}, succeeded={}, failed={}",
                items.size(),
                succeededIds.size(),
                failures.size()
        );
        return new CatalogImportSummary(items.size(), succeededIds.size(), failures.size(), succeededIds, failures);
    }

    private Long importOne(Object item) {
        if (item instanceof CatalogProductCreateRequest product) {
            return catalogImportService.importOne(product);
        }
        throw new IllegalArgumentException("Unsupported catalog import item type: " + (item == null ? "null" : item.getClass().getName()));
    }

    private String itemTitle(Object item) {
        if (item instanceof CatalogProductCreateRequest product) {
            return product.title();
        }
        return String.valueOf(item);
    }

    @Override
    public CatalogImportSummary importJson(CatalogProductJsonImportRequest request) {
        CatalogProductCreateRequest mapped = jsonImportMapper.toCreateRequest(request);
        return importBatch(new CatalogImportRequest(List.of(mapped)));
    }

    @Override
    public void requestAttributeExtraction(Long productId) {
        CatalogProductRecord record = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("catalog_product 不存在: " + productId));
        String payload = jsonCodec.write(Map.of(
                "triggeredBy", MANUAL_TRIGGER,
                "enqueuedAtMs", System.currentTimeMillis()
        ));
        attributeOutboxRepository.enqueue(record.id(), "REFRESH_ATTRIBUTES", payload);
        log.info(
                "catalog attribute extraction requested manually: productId={}",
                productId
        );
    }
}
