package com.bytedance.ai.graph.cartmanage.subgraph;

import com.bytedance.ai.graph.cartmanage.ProductCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认候选商品选择服务，使用 LLM 从候选列表中选择目标商品。
 */
@Service
public class DefaultCandidateSelectionLlmService implements CandidateSelectionLlmService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCandidateSelectionLlmService.class);
    private static final Pattern INDEX_OUTPUT = Pattern.compile("^\\s*index\\s*=\\s*(-?\\d+)\\s*$");

    private final ChatClient intentChatClient;

    public DefaultCandidateSelectionLlmService(@Qualifier("intentChatClient") ChatClient intentChatClient) {
        this.intentChatClient = intentChatClient;
    }

    @Override
    public Optional<Integer> resolveIndex(String userMessage, List<ProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            String rawOutput = intentChatClient.prompt()
                    .system(systemPrompt())
                    .user(userPrompt(userMessage, candidates))
                    .call()
                    .content();
            Matcher matcher = INDEX_OUTPUT.matcher(rawOutput == null ? "" : rawOutput.trim());
            if (!matcher.matches()) {
                log.info("Candidate selection LLM returned invalid output: {}", rawOutput);
                return Optional.empty();
            }
            return Optional.of(Integer.parseInt(matcher.group(1)));
        } catch (Exception exception) {
            log.warn("Candidate selection LLM failed; falling back to clarification", exception);
            return Optional.empty();
        }
    }

    private String systemPrompt() {
        return """
                你是购物车候选商品选择解析器。
                用户正在从候选商品列表中选择一个商品。
                你只能根据用户消息和候选商品列表判断用户选择的候选序号。
                必须只输出一行：
                index = x

                如果无法确定唯一候选，输出：
                index = -1

                不要输出解释。
                不要输出 JSON。
                不要调用工具。
                不要编造候选。
                如果多个候选都符合，输出 index = -1。
                """;
    }

    private String userPrompt(String userMessage, List<ProductCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户消息：").append(userMessage == null ? "" : userMessage).append("\n\n");
        builder.append("候选列表：\n");
        for (int i = 0; i < candidates.size(); i++) {
            ProductCandidate candidate = candidates.get(i);
            builder.append(i + 1)
                    .append(". 商品名=").append(nullToEmpty(candidate.productName()))
                    .append("; 规格=").append(nullToEmpty(candidate.spec()))
                    .append("; 简介=").append(nullToEmpty(candidate.brief()))
                    .append("; 价格=").append(candidate.price() == null ? "" : candidate.price())
                    .append("; productId=").append(nullToEmpty(candidate.productId()))
                    .append("; skuId=").append(nullToEmpty(candidate.skuId()))
                    .append("; externalRef=").append(nullToEmpty(candidate.externalRef()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
