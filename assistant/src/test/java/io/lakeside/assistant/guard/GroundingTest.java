package io.lakeside.assistant.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.lakeside.assistant.support.Fixtures;

class GroundingTest {

    private final Set<String> amounts = new HashSet<>();
    private final Set<String> refs = new HashSet<>();

    private void evidence(Map<String, ?> payload) {
        Grounding.collectEvidence(Fixtures.MAPPER.valueToTree(payload), amounts, refs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"₹87,905.05", "Rs. 87905.05", "INR 87,905.05", "1,899 rupees", "₹1,899", "you spent 1899.00"})
    void acceptsBackendAmountsInAnyFormat(String text) {
        evidence(Map.of("available_balance", "87905.05", "amount", "1899.00"));
        assertThat(Grounding.check(text, amounts, refs).ok()).isTrue();
    }

    @Test
    void rejectsInventedAmountsAndReferences() {
        evidence(Map.of("amount", "1899.00", "id", "TXN-90012"));
        Grounding.Result r = Grounding.check("That was ₹1,999.00 (ref SR-123456).", amounts, refs);
        assertThat(r.ok()).isFalse();
        assertThat(r.unsupportedAmounts()).containsExactly("1,999.00");
        assertThat(r.unsupportedRefs()).containsExactly("SR-123456");
    }

    @Test
    void numbersInsideExplanationsCountAsEvidence() {
        evidence(Map.of("explanation", "Rs 423.73 fee plus Rs 76.27 GST (18%)."));
        assertThat(Grounding.check("It's ₹423.73 plus ₹76.27 GST.", amounts, refs).ok()).isTrue();
    }

    @Test
    void ignoresNumbersThatAreNotMoney() {
        assertThat(Grounding.check("Delivery in 7 days, card ending 4521, 25 leaves, on 2026-10-17, 18% GST.", amounts, refs).ok())
                .isTrue();
    }

    @Test
    void handlesIndianDigitGroupingAndJsonNumbers() {
        evidence(Map.of("amount", 100000));
        assertThat(Grounding.check("₹1,00,000.00", amounts, refs).ok()).isTrue();
    }
}
