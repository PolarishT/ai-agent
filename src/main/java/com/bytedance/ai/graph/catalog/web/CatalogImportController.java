package com.bytedance.ai.graph.catalog.web;

import com.bytedance.ai.graph.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.graph.catalog.api.CatalogImportRequest;
import com.bytedance.ai.graph.catalog.api.CatalogImportSummary;
import com.bytedance.ai.graph.catalog.api.CatalogProductJsonImportRequest;
import com.bytedance.ai.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog 批量导入接口。
 *
 * <p>请求体为 {@link CatalogImportRequest}（包含多条 SPU 草稿）；
 * 单条失败不影响其它条目，失败明细在响应体的 {@code failures} 字段中返回。
 *
 * <p>{@code /json} 子路径接收外部商品资料 JSON（snake_case 字段），由
 * {@link CatalogCommandFacade#importJson} 内部翻译成 catalog 草稿后复用同一条事务化链路，
 * 因此会一次性落库 catalog_product / catalog_sku / catalog_product_knowledge /
 * catalog_product_faq / catalog_product_review，并触发对应 RAG 文档的离线索引。
 */
@RestController
@Validated
@RequestMapping(value = "/admin/catalog/import",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogImportController {

    private final CatalogCommandFacade catalogCommandFacade;

    public CatalogImportController(CatalogCommandFacade catalogCommandFacade) {
        this.catalogCommandFacade = catalogCommandFacade;
    }

    @PostMapping
    public ApiResponse<CatalogImportSummary> importBatch(@Valid @RequestBody CatalogImportRequest request) {
        CatalogImportSummary summary = catalogCommandFacade.importBatch(request);
        String message = summary.failed() == 0
                ? "批量导入成功"
                : "部分导入失败：成功 " + summary.succeeded() + " 条 / 失败 " + summary.failed() + " 条";
        return ApiResponse.ok(message, summary);
    }

    @PostMapping("/json")
    public ApiResponse<CatalogImportSummary> importJson(@Valid @RequestBody CatalogProductJsonImportRequest request) {
        CatalogImportSummary summary = catalogCommandFacade.importJson(request);
        if (summary.failed() == 0) {
            return ApiResponse.ok("商品已导入并触发 RAG 离线索引", summary);
        }
        String reason = summary.failures().isEmpty() ? "未知失败" : summary.failures().get(0).reason();
        return ApiResponse.ok("商品导入失败：" + reason, summary);
    }
}
