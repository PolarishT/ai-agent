package com.bytedance.ai.agent.intent;

import java.util.regex.Pattern;

final class IntentRules {

    static final Pattern OUT_OF_SCOPE = Pattern.compile(
            ".*(写代码|代码怎么写|讲笑话|股票|基金|新闻|天气|论文|翻译).*",
            Pattern.CASE_INSENSITIVE
    );

    static final Pattern PRICE = Pattern.compile(
            ".*((\\d+(?:\\.\\d+)?)\\s*(元|块)?\\s*(以下|以内|内|之内)|" +
                    "(低于|小于|少于|不超过|预算)\\s*(\\d+(?:\\.\\d+)?)\\s*(元|块)?|" +
                    "(\\d+(?:\\.\\d+)?)\\s*[-~到至]\\s*(\\d+(?:\\.\\d+)?)\\s*(元|块)?).*",
            Pattern.CASE_INSENSITIVE
    );

    static final Pattern RECOMMEND = Pattern.compile(
            ".*(推荐|帮我找|有没有|来款|来个|想买|买什么|适合).*",
            Pattern.CASE_INSENSITIVE
    );

    private IntentRules() {
    }
}
