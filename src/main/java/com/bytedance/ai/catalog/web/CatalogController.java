package com.bytedance.ai.catalog.web;

import com.bytedance.ai.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.catalog.api.CatalogSkuView;
import com.bytedance.ai.catalog.api.CatalogSpuView;
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

    @GetMapping("/public/catalog/spu/{spuId}")
    public ApiResponse<CatalogSpuView> getSpu(@PathVariable @Positive Long spuId) {
        return ApiResponse.ok(catalogQueryFacade.getSpu(spuId));
    }

    @GetMapping("/public/catalog/spu/{spuId}/skus")
    public ApiResponse<List<CatalogSkuView>> listSkus(@PathVariable @Positive Long spuId) {
        return ApiResponse.ok(catalogQueryFacade.listSkus(spuId));
    }

    @PostMapping("/admin/catalog/spu/{spuId}/extract-attributes")
    public ApiResponse<Void> retryExtractAttributes(@PathVariable @Positive Long spuId) {
        catalogCommandFacade.requestAttributeExtraction(spuId);
        return ApiResponse.okMessage("商品属性抽取已重新派发");
    }
}
