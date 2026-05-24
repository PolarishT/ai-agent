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

    static final Pattern COMPARE = Pattern.compile(
            ".*(\\bvs\\b|对比|比较|哪个好|哪个更|哪款|性价比).*",
            Pattern.CASE_INSENSITIVE
    );

    static final Pattern CART = Pattern.compile(
            ".*(购物车|加购|加入|加到|放到|放进|添加|删掉|删除|移除|拿掉|改成\\s*\\d+|" +
                    "数量|几件|查看车|看下车|结算|下单|取消).*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * REFINE 命中词：用户在已有候选基础上"微调"。
     * 仅当 {@code ConversationMemory.lastTurnSpuRefs()} 非空时才升级为 REFINE，
     * 否则退化为 RECOMMEND / FILTER_BY_ATTR。
     */
    static final Pattern REFINE = Pattern.compile(
            ".*(再便宜|再贵|便宜一些|贵一些|便宜些|贵些|价格再低|价格再高|" +
                    "换一个|换个|换成|不要这个|不要这些|" +
                    "只要|只看|筛选|缩小|筛|改成|改为|" +
                    "再看看|还有别的|还有其他|其他款|别的款|" +
                    "刚才那些|刚刚那些|上面那些|这些里面|这几个里面|这里面).*",
            Pattern.CASE_INSENSITIVE
    );

    private IntentRules() {
    }
}
