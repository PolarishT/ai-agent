package com.bytedance.ai.document.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 文档导入请求。
 *
 * <p>离线商品 JSON 导入不会直接写入向量库；catalog 模块会先把商品、FAQ、评价等 JSON 字段
 * 渲染成多篇 Markdown 文档，再通过 {@link DocumentCommandFacade#createDocument(RagDocumentCreateRequest)}
 * 进入 document/indexing 链路。因此该请求对象同时服务于手工 JSON API、Markdown 文件上传和
 * catalog 离线导入生成的 Markdown 文档。
 *
 * @param sourceType  文档来源类型
 * @param sourceUri   文档来源 URI
 * @param externalRef 外部引用标识
 * @param title       文档标题
 * @param content     文档正文
 * @param metadata    文档附加元数据
 */
public record RagDocumentCreateRequest(
        @NotBlank(message = "来源类型不能为空")
        String sourceType,
        @NotBlank(message = "来源链接不能为空")
        @Size(max = 255, message = "来源链接长度不能超过 255 个字符")
        String sourceUri,
        @Size(max = 255, message = "外部引用长度不能超过 255 个字符")
        String externalRef,
        @NotBlank(message = "标题不能为空")
        @Size(max = 100, message = "标题长度不能超过 100 个字符")
        @Pattern(
                // 忽略大小写，拦截 <script, <iframe, <object, javascript: 以及 onload= 等事件注入
                regexp = "^(?!.*(?i)(<script|<iframe|<object|<embed|javascript:|on[a-zA-Z]+\\s*=)).*$",
                message = "标题不能包含特殊脚本字符或不安全的 HTML 标签"
        )
        String title,
        @NotBlank(message = "文档内容不能为空")
        String content,
        @NotEmpty(message = "元数据信息不能为空")
        Map<String, Object> metadata
) {
}
