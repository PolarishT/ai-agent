package com.bytedance.ai.graph.catalog.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量导入请求：携带若干 Product 草稿。
 *
 * @param items Product 列表
 */
public record CatalogImportRequest(
        @NotEmpty(message = "至少需要一条商品")
        @Valid
        List<?> items
) {
}
