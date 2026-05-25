package com.bytedance.ai.agent.workflow;

import com.bytedance.ai.agent.api.Slot;
import com.bytedance.ai.agent.api.SpuCardView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a user's raw message into the paused workflow state so the engine can resume the previous node.
 *
 * <p>Pure Java parsing — no LLM call. Recognizes Chinese ordinal selections, simple confirmation tokens,
 * and a small set of slot补充 (predominantly price range / brand keywords).
 */
@Component
public class ResumeInputParser {

    private static final Map<String, Integer> CN_ORDINAL = ordinalMap();
    private static final Pattern ARABIC_ORDINAL = Pattern.compile("第\\s*([0-9]{1,2})\\s*[个款种件号]");
    private static final Pattern PRICE_RANGE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*[到至-]\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern PRICE_UNDER = Pattern.compile("(?:不超过|不高于|低于|预算)\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final List<String> CONFIRM_TOKENS = List.of("确认", "确定", "下单", "买它", "买了", "好的", "是的", "ok", "确认下单");
    private static final List<String> CANCEL_TOKENS = List.of("取消", "不要了", "不下单", "算了");

    public void apply(WorkflowRuntimeState state, String message) {
        if (message == null || message.isBlank() || !state.isPaused()) {
            return;
        }
        switch (state.status()) {
            case WAITING_SELECTION -> applySelection(state, message);
            case WAITING_CONFIRMATION -> applyConfirmation(state, message);
            case WAITING_SLOT -> applySlot(state, message);
            default -> {
            }
        }
    }

    private void applySelection(WorkflowRuntimeState state, String message) {
        PendingSelection pending = state.pendingSelection();
        if (pending == null || pending.candidates().isEmpty()) {
            return;
        }
        Integer index = resolveOrdinal(message);
        if (index == null || index < 1 || index > pending.candidates().size()) {
            return;
        }
        SpuCardView candidate = pending.candidates().get(index - 1);
        state.pendingSelection(pending.withSelection(candidate.externalRef(), candidate.spuId()));
        state.status(WorkflowStatus.RUNNING);
    }

    private void applyConfirmation(WorkflowRuntimeState state, String message) {
        String text = message.trim().toLowerCase();
        PendingConfirmation pending = state.pendingConfirmation();
        if (pending == null) {
            return;
        }
        if (CANCEL_TOKENS.stream().anyMatch(text::contains)) {
            state.pendingConfirmation(pending.withConfirmed(false));
            state.status(WorkflowStatus.END);
            state.answerText("已为您取消该操作。");
            return;
        }
        if (CONFIRM_TOKENS.stream().anyMatch(text::contains)) {
            state.pendingConfirmation(pending.withConfirmed(true));
            state.status(WorkflowStatus.RUNNING);
        }
    }

    private void applySlot(WorkflowRuntimeState state, String message) {
        Slot existing = state.slots();
        Slot.PriceRange newRange = parsePriceRange(message);
        List<String> brands = mergeBrands(existing.brands(), message);
        if (newRange != null || !brands.equals(existing.brands())) {
            Slot merged = new Slot(
                    existing.must(),
                    existing.mustNot(),
                    newRange != null ? newRange : existing.priceRange(),
                    existing.categoryHint(),
                    brands,
                    existing.scenario()
            );
            state.slots(merged);
            state.status(WorkflowStatus.RUNNING);
        }
    }

    private Integer resolveOrdinal(String message) {
        String text = message.trim();
        Matcher arabic = ARABIC_ORDINAL.matcher(text);
        if (arabic.find()) {
            return Integer.parseInt(arabic.group(1));
        }
        for (Map.Entry<String, Integer> entry : CN_ORDINAL.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        try {
            int direct = Integer.parseInt(text);
            return direct;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Slot.PriceRange parsePriceRange(String message) {
        Matcher range = PRICE_RANGE.matcher(message);
        if (range.find()) {
            return new Slot.PriceRange(new BigDecimal(range.group(1)), new BigDecimal(range.group(2)));
        }
        Matcher under = PRICE_UNDER.matcher(message);
        if (under.find()) {
            return new Slot.PriceRange(null, new BigDecimal(under.group(1)));
        }
        return null;
    }

    private List<String> mergeBrands(List<String> existing, String message) {
        List<String> merged = new ArrayList<>(existing);
        Pattern brandPattern = Pattern.compile("品牌[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9]+)");
        Matcher matcher = brandPattern.matcher(message);
        while (matcher.find()) {
            String brand = matcher.group(1);
            if (StringUtils.hasText(brand) && !merged.contains(brand)) {
                merged.add(brand);
            }
        }
        return merged;
    }

    private static Map<String, Integer> ordinalMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("第一", 1);
        map.put("第二", 2);
        map.put("第三", 3);
        map.put("第四", 4);
        map.put("第五", 5);
        map.put("第六", 6);
        map.put("第七", 7);
        map.put("第八", 8);
        map.put("第九", 9);
        map.put("第十", 10);
        map.put("最后一", -1);
        return map;
    }
}
