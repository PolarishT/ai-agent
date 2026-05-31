package com.bytedance.ai.graph.catalog.web;

import com.bytedance.ai.graph.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.graph.catalog.api.CatalogProductView;
import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.common.api.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户端落地页 + 运营侧人工触发的 catalog 接口。
 *
 * <p>读侧路径 {@code /public/catalog/...} 与现有 RAG 公共接口对齐；
 * 写侧路径 {@code /admin/catalog/...} 用于后台运营（属性重抽等）。
 *
 * <p>随 catalog 域由 SPU 迁移到 {@code catalog_product}，对外路径与视图统一改为 product 语义。
 */
@RestController
@Validated
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogController {

    private final CatalogQueryFacade catalogQueryFacade;
    private final CatalogCommandFacade catalogCommandFacade;

    public CatalogController(CatalogQueryFacade catalogQueryFacade, CatalogCommandFacade catalogCommandFacade) {
        this.catalogQueryFacade = catalogQueryFacade;
        this.catalogCommandFacade = catalogCommandFacade;
    }

    @GetMapping("/public/catalog/product/{productId}")
    public ApiResponse<CatalogProductView> getProduct(@PathVariable @Positive Long productId) {
        return ApiResponse.ok(catalogQueryFacade.getProduct(productId));
    }

    @GetMapping("/public/catalog/product/{productId}/skus")
    public ApiResponse<List<CatalogSkuView>> listSkus(@PathVariable @Positive Long productId) {
        return ApiResponse.ok(catalogQueryFacade.listSkus(productId));
    }

    @PostMapping("/admin/catalog/product/{productId}/extract-attributes")
    public ApiResponse<Void> retryExtractAttributes(@PathVariable @Positive Long productId) {
        catalogCommandFacade.requestAttributeExtraction(productId);
        return ApiResponse.okMessage("商品属性抽取已重新派发");
    }
}
