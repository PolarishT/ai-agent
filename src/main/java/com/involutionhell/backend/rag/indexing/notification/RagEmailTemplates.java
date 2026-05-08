package com.involutionhell.backend.rag.indexing.notification;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 生产级响应式邮件模板
 * 适配移动端/iPad，采用现代卡片化设计
 */
final class RagEmailTemplates {

    private static final String BRAND_COLOR = "#0969da";
    private static final String DANGER_COLOR = "#cf222e";
    private static final String WARNING_COLOR = "#9a6700";

    private RagEmailTemplates() {
    }

    static String finalFailureHtml(String title, String description, List<Field> fields, String timelineUrl, List<String> suggestions) {
        return render(new Template("索引任务最终失败", title, description, "严重错误", DANGER_COLOR, fields,
                StringUtils.hasText(timelineUrl) ? new Action("查看索引时间线", timelineUrl) : null, "修复建议", suggestions));
    }

    static String parseFailureHtml(String title, String description, List<Field> fields, List<String> suggestions) {
        return render(new Template("消息解析异常告警", title, description, "告警", WARNING_COLOR, fields, null, "排查建议", suggestions));
    }

    private static String render(Template template) {
        StringBuilder html = new StringBuilder(10_240);
        html.append("""
                        <!doctype html>
                        <html lang="zh-CN">
                        <head>
                          <meta charset="UTF-8">
                          <meta name="viewport" content="width=device-width, initial-scale=1.0">
                          <style>
                            @media only screen and (max-width: 600px) {
                              .container { border-radius: 0 !important; width: 100% !important; }
                              .mobile-padding { padding: 20px !important; }
                            }
                          </style>
                        </head>
                        <body style="margin:0;padding:0;background-color:#f6f8fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                          <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background-color:#f6f8fa;padding:20px 10px;">
                            <tr>
                              <td align="center">
                                <div class="container" style="max-width:600px;background-color:#ffffff;border:1px solid #d0d7de;border-radius:8px;overflow:hidden;text-align:left;">
                        
                                  <div style="padding:24px;background-color:#0d1117;color:#ffffff;">
                                    <div style="font-size:12px;color:#8b949e;font-weight:600;text-transform:uppercase;letter-spacing:0.05em;margin-bottom:8px;">RAG Indexing Service</div>
                                    <h1 style="margin:0;font-size:22px;font-weight:700;">""").append(escape(template.title())).append("""
                          </h1>
                        </div>
                        
                        <div class="mobile-padding" style="padding:28px;">
                        
                          <div style="margin-bottom:24px;">
                            <span style="display:inline-block;padding:4px 10px;border-radius:4px;font-size:12px;font-weight:700;color:#ffffff;background-color:""")
                .append(template.badgeColor()).append(";\">")
                .append(escape(template.badge())).append("""
                        </span>
                        <p style="margin:16px 0 0;font-size:16px;line-height:1.6;color:#24292f;font-weight:500;">""")
                .append(escape(template.description())).append("""
                                      </p>
                                    </div>
                        """);

        appendFields(html, template.fields());
        appendAction(html, template.action());
        appendSuggestions(html, template.suggestionTitle(), template.suggestions());

        html.append("""
                            <div style="margin-top:40px;padding-top:20px;border-top:1px solid #d8dee4;font-size:12px;color:#6e7781;line-height:1.6;">
                              <p style="margin:0;">此邮件由系统自动发出，请勿回复。</p>
                              <p style="margin:4px 0 0;">© 2026 InvolutionHell RAG System</p>
                            </div>
                          </div>
                        </div>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """);
        return html.toString();
    }

    private static void appendFields(StringBuilder html, List<Field> fields) {
        if (fields == null || fields.isEmpty()) return;
        html.append("<div style=\"border:1px solid #d8dee4;border-radius:6px;overflow:hidden;margin-top:24px;\">");
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            String border = (i == fields.size() - 1) ? "" : "border-bottom:1px solid #d8dee4;";
            String bg = (i % 2 == 0) ? "#ffffff" : "#f8f9fa";
            html.append("<div style=\"padding:14px;background-color:").append(bg).append(";").append(border).append("\">")
                    .append("<div style=\"font-size:12px;font-weight:600;color:#57606a;margin-bottom:6px;text-transform:uppercase;\">")
                    .append(escape(field.label())).append("</div>")
                    .append("<div style=\"font-size:14px;color:#1f2328;word-break:break-all;line-height:1.5;\">")
                    .append(formatValue(field.value(), field.multiline()))
                    .append("</div></div>");
        }
        html.append("</div>");
    }

    private static void appendAction(StringBuilder html, Action action) {
        if (action == null || !StringUtils.hasText(action.url())) return;
        html.append("<div style=\"margin-top:28px;text-align:center;\">")
                .append("<a href=\"").append(escape(action.url())).append("\" ")
                .append("style=\"display:inline-block;padding:12px 24px;background-color:").append(BRAND_COLOR).append(";color:#ffffff;text-decoration:none;font-size:14px;font-weight:600;border-radius:6px;\">")
                .append(escape(action.label())).append("</a></div>");
    }

    private static void appendSuggestions(StringBuilder html, String title, List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) return;
        html.append("<div style=\"margin-top:28px;padding:20px;background-color:#fff8c5;border:1px solid #eac54f;border-radius:8px;\">")
                .append("<div style=\"font-size:14px;font-weight:700;color:#1f2328;margin-bottom:10px;\">").append(escape(title)).append("</div>")
                .append("<ul style=\"margin:0;padding-left:20px;font-size:14px;color:#3b2300;line-height:1.6;\">");
        for (String s : suggestions) {
            html.append("<li style=\"margin-bottom:4px;\">").append(escape(s)).append("</li>");
        }
        html.append("</ul></div>");
    }

    private static String formatValue(String value, boolean multiline) {
        String safeValue = StringUtils.hasText(value) ? value : "-";
        if (!multiline) return escape(safeValue);
        return "<pre style=\"margin:8px 0 0;white-space:pre-wrap;word-break:break-all;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;line-height:1.5;color:#1f2328;background-color:rgba(175,184,193,0.1);padding:12px;border-radius:4px;border:1px solid #d0d7de;\">"
                + escape(safeValue) + "</pre>";
    }

    private static String escape(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    record Field(String label, String value, boolean multiline) {
    }

    record Action(String label, String url) {
    }

    record Template(
            String preheader, String title, String description,
            String badge, String badgeColor, List<Field> fields,
            Action action, String suggestionTitle, List<String> suggestions
    ) {
    }
}