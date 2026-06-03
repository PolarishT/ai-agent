package com.bytedance.ai.graph.ordermanage.application;

import com.bytedance.ai.graph.ordermanage.AddressParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderAddressResolverTest {

    private final OrderAddressResolver resolver = new OrderAddressResolver();

    @Test
    void parseNameAndPhoneWithoutAddressReturnsMissingAddress() {
        AddressParseResult result = resolver.parse("收货人张震霆 18080266036");

        assertThat(result.complete()).isFalse();
        assertThat(result.missingFields()).containsExactly("addressText");
        assertThat(result.snapshot().receiverName()).isEqualTo("张震霆");
        assertThat(result.snapshot().phone()).isEqualTo("18080266036");
    }

    @Test
    void parseBlankInputReturnsAllMissingFields() {
        AddressParseResult result = resolver.parse("");

        assertThat(result.complete()).isFalse();
        assertThat(result.missingFields()).containsExactly("receiverName", "phone", "addressText");
    }
}
