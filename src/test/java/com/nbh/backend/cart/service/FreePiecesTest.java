package com.nbh.backend.cart.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** "Buy 5 get 3 free" (owner, 2026-11-04): how many free pieces, and which shades. */
class FreePiecesTest {

    private final UUID pink = UUID.randomUUID();
    private final UUID red = UUID.randomUUID();
    private final UUID clear = UUID.randomUUID();

    private static Map<UUID, BigDecimal> of(Object... pairs) {
        Map<UUID, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((UUID) pairs[i], BigDecimal.valueOf((Integer) pairs[i + 1]));
        return m;
    }

    private final Map<UUID, BigDecimal> plenty = of(pink, 100, red, 100, clear, 100);

    @Test
    void fivePaidGiveThreeFreeOfTheShadeBoughtMost() {
        var plan = FreePieces.plan(5, 3, of(pink, 2, red, 3, clear, 0), Map.of(), plenty);
        assertThat(plan).containsOnlyKeys(red);
        assertThat(plan.get(red)).isEqualByComparingTo("3");
    }

    @Test
    void fourPaidGiveNothing() {
        assertThat(FreePieces.plan(5, 3, of(pink, 4), Map.of(), plenty)).isEmpty();
    }

    @Test
    void tenPaidGiveSixFree() {
        var plan = FreePieces.plan(5, 3, of(pink, 10), Map.of(), plenty);
        assertThat(FreePieces.sum(plan)).isEqualByComparingTo("6");
    }

    @Test
    void theShoppersOwnChoiceOfShadesIsKept() {
        var plan = FreePieces.plan(5, 3, of(pink, 5, red, 0), of(red, 1, clear, 2), plenty);
        assertThat(plan).isEqualTo(of(red, 1, clear, 2));
    }

    @Test
    void payingForFewerTakesFromTheNewestChoiceFirst() {
        // was 10 paid with 6 free (red 3 then clear 3); now 7 paid: 3 free, the clear ones go
        var plan = FreePieces.plan(5, 3, of(pink, 7), of(red, 3, clear, 3), plenty);
        assertThat(plan).isEqualTo(of(red, 3));
    }

    @Test
    void freePiecesOnlyComeFromWhatIsLeftOnTheShelf() {
        // pink bought most, but its shelf holds 6 and 5 are paid for: 1 free pink, the rest from the next with stock
        var plan = FreePieces.plan(5, 3, of(pink, 5, red, 0), Map.of(), of(pink, 6, red, 10));
        assertThat(plan.get(pink)).isEqualByComparingTo("1");
        assertThat(plan.get(red)).isEqualByComparingTo("2");
    }

    @Test
    void noStockAnywhereGivesWhatThereIs() {
        var plan = FreePieces.plan(5, 3, of(pink, 5), Map.of(), of(pink, 6));
        assertThat(FreePieces.sum(plan)).isEqualByComparingTo("1");
    }
}
