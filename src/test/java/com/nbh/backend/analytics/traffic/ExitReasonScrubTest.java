package com.nbh.backend.analytics.traffic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExitReasonScrubTest {

    @Test
    void trimsAndCollapsesWhitespace() {
        assertThat(ExitReasonService.scrub("  too \n  slow\t here ")).isEqualTo("too slow here");
        assertThat(ExitReasonService.scrub("   ")).isNull();
        assertThat(ExitReasonService.scrub(null)).isNull();
    }

    @Test
    void replacesEmailAddresses() {
        assertThat(ExitReasonService.scrub("write to jane.doe+shop@mail.example.co.ke please"))
                .isEqualTo("write to [email] please");
    }

    @Test
    void replacesLongDigitRunsEvenWithSeparators() {
        assertThat(ExitReasonService.scrub("call 0712345678 now")).isEqualTo("call [number] now");
        assertThat(ExitReasonService.scrub("call +254 712 345 678 now")).isEqualTo("call [number] now");
        assertThat(ExitReasonService.scrub("call 0712-345-678.")).isEqualTo("call [number].");
        assertThat(ExitReasonService.scrub("1234567")).isEqualTo("[number]");
    }

    @Test
    void keepsShortNumbers() {
        assertThat(ExitReasonService.scrub("fee was 350 not 120")).isEqualTo("fee was 350 not 120");
        assertThat(ExitReasonService.scrub("123456")).isEqualTo("123456");
    }

    @Test
    void cutsToThreeHundredCharacters() {
        assertThat(ExitReasonService.scrub("a".repeat(500))).hasSize(300);
    }
}
