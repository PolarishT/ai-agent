package com.bytedance.ai.graph.intent.service;

import com.bytedance.ai.graph.intent.MainIntentDecision;
import com.bytedance.ai.shared.support.RagLogFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class MainIntentRouterService {

    private static final Logger log = LoggerFactory.getLogger(MainIntentRouterService.class);

    private final ChatClient intentChatClient;
    private final MainIntentPromptFactory promptFactory;
    private final MainIntentDecisionNormalizer normalizer;

    public MainIntentRouterService(

            @Qualifier("intentChatClient") ChatClient intentChatClient,
            MainIntentDecisionNormalizer normalizer,
            MainIntentPromptFactory promptFactory

    ) {

        this.intentChatClient = intentChatClient;
        this.normalizer = normalizer;
        this.promptFactory = promptFactory;

    }

    public MainIntentDecision route(String userMessage, String conversationMemory) {
        // todo memory 管理
        String prompt = promptFactory.build(userMessage, conversationMemory);

        MainIntentDecision rawOutput;
        try {
            rawOutput = intentChatClient.prompt(prompt).call().entity(MainIntentDecision.class);
        } catch (Exception e) {
            log.error("main intent LLM call failed", e);
            throw e;
        }
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.llm.raw_output")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue("main_intent.llm_output", rawOutput)
                .log("main intent LLM raw output: {}", rawOutput);

        MainIntentDecision normalizedDecision = normalizer.normalize(rawOutput);
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "main_intent.normalized")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue("main_intent.intent", normalizedDecision.intent().name())
                .addKeyValue("main_intent.confidence", normalizedDecision.confidence())
                .addKeyValue("main_intent.need_clarify", normalizedDecision.needClarify())
                .addKeyValue("main_intent.write_action", normalizedDecision.writeAction())
                .addKeyValue("main_intent.target_workflow", normalizedDecision.targetWorkflow())
                .addKeyValue("main_intent.missing_slots", normalizedDecision.missingSlots())
                .log("main intent normalized: intent={}, confidence={}, needClarify={}, writeAction={}, targetWorkflow={}, missingSlots={}, reason={}",
                        normalizedDecision.intent(),
                        normalizedDecision.confidence(),
                        normalizedDecision.needClarify(),
                        normalizedDecision.writeAction(),
                        normalizedDecision.targetWorkflow(),
                        normalizedDecision.missingSlots(),
                        normalizedDecision.reason());
        return normalizedDecision;
    }
}
