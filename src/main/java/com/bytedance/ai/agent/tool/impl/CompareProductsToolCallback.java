package com.bytedance.ai.agent.tool.impl;

import com.bytedance.ai.agent.api.IntentType;
import com.bytedance.ai.agent.tool.AgentToolCallback;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CompareProductsToolCallback implements AgentToolCallback {

    public static final String TOOL_NAME = "compare_products";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "query":{
                  "type":"string",
                  "description":"保留用户原文，不要改写、清洗或补充。"
                },
                "spuIds":{
                  "type":"array",
                  "items":{"type":"integer"},
                  "description":"只填写用户明确给出的 SPU ID，不要猜测、推断或编造。"
                },
                "externalRefs":{
                  "type":"array",
                  "items":{"type":"string"},
                  "description":"只填写用户明确给出的外部商品编号，不要猜测、推断或编造。"
                },
                "productKeywords":{
                  "type":"array",
                  "items":{"type":"string"},
                  "description":"填写用户提到的商品名、品牌、型号、别名等检索关键词；不要填写价格、库存、品牌事实或商品属性事实。"
                },
                "topK":{
                  "type":"integer",
                  "minimum":2,
                  "maximum":5,
                  "description":"期望对比的商品数量，工具会限制在 2 到 5。"
                },
                "compareAspects":{
                  "type":"array",
                  "items":{"type":"string"},
                  "description":"用户关注的对比维度，例如价格、库存、品牌、成分、保湿、敏感肌适配、性价比、肤感。"
                },
                "userGoal":{
                  "type":"string",
                  "description":"用户选择目标，例如预算优先、保湿优先、敏感肌优先、综合推荐。"
                }
              },
              "required":["query"],
              "description":"禁止编造价格、库存、品牌、成分、功效、肤感等商品事实；这些事实只能由工具查库返回。"
            }
            """;

    private final CompareProductsService compareProductsService;
    private final RagJsonCodec jsonCodec;

    public CompareProductsToolCallback(CompareProductsService compareProductsService, RagJsonCodec jsonCodec) {
        this.compareProductsService = compareProductsService;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("对比 2 到 5 个商品，基于商品库真实字段返回商品卡片和对比矩阵")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        CompareProductsInput input = jsonCodec.read(toolInput, CompareProductsInput.class);
        return jsonCodec.write(compareProductsService.compare(input));
    }

    @Override
    public Set<IntentType> handles() {
        return Set.of(IntentType.COMPARE);
    }
}
