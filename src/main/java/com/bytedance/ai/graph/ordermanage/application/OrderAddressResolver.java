package com.bytedance.ai.graph.ordermanage.application;

import com.bytedance.ai.graph.ordermanage.AddressSnapshot;
import com.bytedance.ai.graph.ordermanage.AddressParseResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订单地址解析器，从用户输入和历史地址中得到下单地址快照。
 */
@Component
public class OrderAddressResolver {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)((?:1[3-9]\\d{9})|(?:04\\d{8})|(?:\\d{8,}))(?!\\d)");
    private static final Pattern RECEIVER_PATTERN = Pattern.compile("(?:收货人|联系人|姓名|receiver|name)[:：\\s]*([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z\\s]{1,30})", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSTCODE_PATTERN = Pattern.compile("(?<!\\d)(\\d{4,6})(?!\\d)");

    public AddressParseResult parse(String message) {
        String source = message == null ? "" : message.trim();
        String phone = find(PHONE_PATTERN, source);
        String receiverName = find(RECEIVER_PATTERN, source);
        if (!StringUtils.hasText(receiverName)) {
            receiverName = inferName(source, phone);
        }
        String addressText = normalizeAddress(source, phone, receiverName);
        String postcode = find(POSTCODE_PATTERN, addressText);
        String city = inferCity(addressText);
        String state = inferState(addressText);

        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(receiverName)) {
            missing.add("receiverName");
        }
        if (!StringUtils.hasText(phone)) {
            missing.add("phone");
        }
        if (!StringUtils.hasText(addressText)) {
            missing.add("addressText");
        }
        return new AddressParseResult(
                missing.isEmpty(),
                List.copyOf(missing),
                new AddressSnapshot(receiverName, phone, addressText, postcode, city, state)
        );
    }

    public boolean looksLikeAddress(String message) {
        String text = message == null ? "" : message.trim();
        return text.contains("地址")
                || text.contains("寄到")
                || text.contains("送到")
                || text.contains("收货")
                || PHONE_PATTERN.matcher(text).find()
                || text.length() >= 12;
    }

    public String missingFieldsMessage(List<String> missingFields) {
        List<String> messages = new ArrayList<>();
        if (missingFields.contains("receiverName")) {
            messages.add("请补充收货人姓名");
        }
        if (missingFields.contains("phone")) {
            messages.add("请补充联系电话");
        }
        if (missingFields.contains("addressText")) {
            messages.add("请补充详细收货地址");
        }
        return String.join("，", messages) + "。";
    }

    private String find(Pattern pattern, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String inferName(String source, String phone) {
        String cleaned = source;
        if (phone != null) {
            cleaned = cleaned.replace(phone, " ");
        }
        String[] parts = cleaned.split("[,，、\\s]+");
        for (String part : parts) {
            String value = part.trim();
            if (value.matches("[\\p{IsHan}]{2,4}") || value.matches("[A-Za-z]{2,30}")) {
                if (!value.contains("地址") && !value.contains("电话") && !value.contains("寄到") && !value.contains("送到")) {
                    return value;
                }
            }
        }
        return null;
    }

    private String normalizeAddress(String source, String phone, String receiverName) {
        String result = source;
        if (phone != null) {
            result = result.replace(phone, " ");
        }
        if (receiverName != null) {
            result = result.replaceAll("(收货人|联系人|姓名|receiver|name)[:：\\s]*" + Pattern.quote(receiverName), " ");
        }
        result = result
                .replace("收货地址是", " ")
                .replace("地址是", " ")
                .replace("地址：", " ")
                .replace("地址:", " ")
                .replace("寄到", " ")
                .replace("送到", " ")
                .replace("我的地址", " ")
                .replace(receiverName == null ? "\u0000" : receiverName, " ")
                .replaceAll("[,，、]+", " ")
                .trim();
        return result.isBlank() ? null : result;
    }

    private String inferCity(String addressText) {
        if (addressText == null) {
            return null;
        }
        for (String city : List.of("Sydney", "Melbourne", "Brisbane", "Perth", "Adelaide", "Canberra", "北京", "上海", "广州", "深圳")) {
            if (addressText.toLowerCase().contains(city.toLowerCase())) {
                return city;
            }
        }
        return null;
    }

    private String inferState(String addressText) {
        if (addressText == null) {
            return null;
        }
        for (String state : List.of("NSW", "VIC", "QLD", "WA", "SA", "ACT", "TAS", "NT")) {
            if (addressText.toUpperCase().contains(state)) {
                return state;
            }
        }
        return null;
    }
}
