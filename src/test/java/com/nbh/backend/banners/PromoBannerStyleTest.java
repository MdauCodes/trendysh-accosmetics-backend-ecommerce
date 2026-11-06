package com.nbh.backend.banners;

import com.nbh.backend.common.exception.UnprocessableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The advanced banner look (owner, 2026-10-31): only what the shop can draw is kept, and it saves and reads back. */
class PromoBannerStyleTest {

    private static PromoBannerService.Style style(String headlineColor, String size, String align, String shade) {
        return new PromoBannerService.Style(headlineColor, null, size, align, shade, null, null, null, null);
    }

    @Test
    void onlyKnownChoicesAndRealColoursAreKept() {
        PromoBannerService.Style s = PromoBannerService.Style.clean(style(" #c2185b ", "l", "CENTER", "strong"));
        assertThat(s.headlineColor()).isEqualTo("#C2185B");
        assertThat(s.headlineSize()).isEqualTo("L");
        assertThat(s.align()).isEqualTo("center");
        assertThat(s.shade()).isEqualTo("strong");
        assertThatThrownBy(() -> PromoBannerService.Style.clean(style("red", null, null, null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("#C2185B");
        assertThatThrownBy(() -> PromoBannerService.Style.clean(style(null, "XXL", null, null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("S, M, L, XL");
    }

    private static PromoBannerService.Style withExtras(PromoBannerService.Style.Gradient g, PromoBannerService.Style phone,
                                                       Integer focusX, Integer focusY) {
        return new PromoBannerService.Style(null, null, "L", null, null, null, null, null, null, null, null, null, g, phone,
                focusX, focusY);
    }

    @Test
    void blendedColoursAndThePhonesOwnLookAreKept() {
        var phone = withExtras(new PromoBannerService.Style.Gradient(java.util.List.of("#111111", "#c8157a"), "DOWN"),
                null, 120, -5);
        var s = PromoBannerService.Style.clean(withExtras(
                new PromoBannerService.Style.Gradient(java.util.List.of("#0b5d3b", " #3a0b2c", "#F5B83D"), "centre"),
                phone, 40, 40));
        assertThat(s.gradient().colors()).containsExactly("#0B5D3B", "#3A0B2C", "#F5B83D");
        assertThat(s.gradient().direction()).isEqualTo("centre");
        assertThat(s.focusX()).as("only the phone keeps its own focus").isNull();
        assertThat(s.phone().gradient().direction()).isEqualTo("down");
        assertThat(s.phone().focusX()).isEqualTo(100);
        assertThat(s.phone().focusY()).isEqualTo(0);
        assertThat(s.phone().phone()).isNull();
        assertThat(PromoBannerService.readStyle(PromoBannerService.writeStyle(s))).isEqualTo(s);
        assertThat(PromoBannerService.Style.clean(withExtras(
                new PromoBannerService.Style.Gradient(java.util.List.of("#111111"), null), null, null, null)).gradient())
                .as("one colour is no blend").isNull();
        assertThatThrownBy(() -> PromoBannerService.Style.clean(withExtras(new PromoBannerService.Style.Gradient(
                java.util.List.of("#111111", "#222222", "#333333", "#444444"), null), null, null, null)))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void anEmptyLookIsTheAutomaticOne() {
        assertThat(PromoBannerService.Style.clean(style(null, " ", "", null))).isNull();
        assertThat(PromoBannerService.Style.clean(null)).isNull();
    }

    @Test
    void aLookSavesAndReadsBackAndABrokenOneFallsBackToAutomatic() {
        PromoBannerService.Style s = PromoBannerService.Style.clean(
                new PromoBannerService.Style("#111111", "#FFFFFF", "M", "left", "light", "#FFF1F5", "#C2185B",
                        "#FFFFFF", "display"));
        String json = PromoBannerService.writeStyle(s);
        assertThat(json).doesNotContain("null");
        assertThat(PromoBannerService.readStyle(json)).isEqualTo(s);
        assertThat(PromoBannerService.readStyle("{not json")).isNull();
        assertThat(PromoBannerService.writeStyle(null)).isNull();
    }

    @Test
    void moreFontsWeightAndCapitalsAreKept() {
        PromoBannerService.Style s = PromoBannerService.Style.clean(new PromoBannerService.Style(null, null, "xl", null,
                null, null, null, null, "Condensed", "BLACK", "upper"));
        assertThat(s.headlineSize()).isEqualTo("XL");
        assertThat(s.font()).isEqualTo("condensed");
        assertThat(s.weight()).isEqualTo("black");
        assertThat(s.caps()).isEqualTo("upper");
        assertThat(PromoBannerService.readStyle(PromoBannerService.writeStyle(s))).isEqualTo(s);
        assertThatThrownBy(() -> PromoBannerService.Style.clean(new PromoBannerService.Style(null, null, null, null, null,
                null, null, null, "comic", null, null))).isInstanceOf(UnprocessableException.class);
    }

    @Test
    void eachGroupOfWordsKeepsItsOwnLook() {
        var parts = new java.util.HashMap<String, PromoBannerService.Style.Part>();
        parts.put("tag", new PromoBannerService.Style.Part("script", "L", null, null, "#ffe08a"));
        parts.put("price", new PromoBannerService.Style.Part(null, null, "black", "upper", null));
        parts.put("offer", new PromoBannerService.Style.Part(null, null, null, null, null)); // nothing set: dropped
        PromoBannerService.Style s = PromoBannerService.Style.clean(new PromoBannerService.Style(null, null, null, null,
                null, null, null, null, null, null, null, parts));
        assertThat(s.parts()).containsOnlyKeys("tag", "price");
        assertThat(s.parts().get("tag").color()).isEqualTo("#FFE08A");
        assertThat(PromoBannerService.readStyle(PromoBannerService.writeStyle(s))).isEqualTo(s);
        var bad = new java.util.HashMap<String, PromoBannerService.Style.Part>();
        bad.put("footer", new PromoBannerService.Style.Part("sans", null, null, null, null));
        assertThatThrownBy(() -> PromoBannerService.Style.clean(new PromoBannerService.Style(null, null, null, null, null,
                null, null, null, null, null, null, bad))).isInstanceOf(UnprocessableException.class);
    }
}
