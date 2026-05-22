package com.bytedance.ai.catalog.web;

import com.bytedance.ai.catalog.api.CatalogCommandFacade;
import com.bytedance.ai.catalog.api.CatalogImportRequest;
import com.bytedance.ai.catalog.api.CatalogImportSummary;
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
}
