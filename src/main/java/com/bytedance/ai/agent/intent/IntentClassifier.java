package com.bytedance.ai.agent.intent;

public interface IntentClassifier {

    IntentClassification classify(String message);
}
