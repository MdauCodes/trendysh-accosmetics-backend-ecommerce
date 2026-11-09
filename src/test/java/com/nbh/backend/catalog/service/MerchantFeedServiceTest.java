package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.CatalogCategoryDto;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The product feed for Google Merchant Center: well-formed, Google's fields, and prices that match the shop's pages. */
class MerchantFeedServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final String SITE = "https://trendyshaccosmetic.com";

    private static MerchantFeedService.Item item(BigDecimal price, BigDecimal sale, Instant ends, String gtin) {
        return new MerchantFeedService.Item("LSH-001", "Lash Glue — Black", "Strong hold & quick dry <b>glue</b>",
                SITE + "/product/abc/", "https://img.trendyshaccosmetic.com/o/products/glue.jpg", true, price, sale, ends,
                "Trendysh", gtin, "group-1", "Black", "10 ml", "Lash supplies > Lash glue");
    }

    private static Document parse(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aDealIsThePriceAndTheSalePriceWithItsEnd() throws Exception {
        String xml = MerchantFeedService.render(List.of(item(new BigDecimal("1000"), new BigDecimal("849"),
                Instant.parse("2026-11-30T21:00:00Z"), null)), SITE, NOW);
        Document d = parse(xml);
        assertThat(d.getElementsByTagNameNS("http://base.google.com/ns/1.0", "price").item(0).getTextContent()).isEqualTo("1000.00 KES");
        assertThat(d.getElementsByTagNameNS("http://base.google.com/ns/1.0", "sale_price").item(0).getTextContent()).isEqualTo("849.00 KES");
        assertThat(xml).contains("<g:sale_price_effective_date>2026-11-05T12:00+03:00/2026-12-01T00:00+03:00</g:sale_price_effective_date>")
                .contains("<g:availability>in_stock</g:availability>")
                .contains("<g:identifier_exists>no</g:identifier_exists>")
                .contains("<g:item_group_id>group-1</g:item_group_id>")
                .contains("Strong hold &amp; quick dry &lt;b&gt;glue&lt;/b&gt;")
                // The ads bid on deals and price bands apart (owner, 2026-11-09); the band is what the shopper pays.
                .contains("<g:custom_label_0>deal</g:custom_label_0>")
                .contains("<g:custom_label_1>500-1999</g:custom_label_1>");
    }

    @Test
    void noDealIsJustThePriceAndARealGtinReplacesIdentifierExists() throws Exception {
        String xml = MerchantFeedService.render(List.of(item(new BigDecimal("240"), null, null, "5901234123457")), SITE, NOW);
        parse(xml);
        assertThat(xml).contains("<g:price>240.00 KES</g:price>").doesNotContain("sale_price")
                .contains("<g:gtin>5901234123457</g:gtin>").doesNotContain("identifier_exists")
                .contains("<g:custom_label_0>regular</g:custom_label_0>")
                .contains("<g:custom_label_1>under-500</g:custom_label_1>");
    }

    @Test
    void priceBandsSplitWhereDeliveryStopsWeighing() {
        assertThat(MerchantFeedService.priceBand(new BigDecimal("499.99"))).isEqualTo("under-500");
        assertThat(MerchantFeedService.priceBand(new BigDecimal("500"))).isEqualTo("500-1999");
        assertThat(MerchantFeedService.priceBand(new BigDecimal("2000"))).isEqualTo("2000-9999");
        assertThat(MerchantFeedService.priceBand(new BigDecimal("10000"))).isEqualTo("10000-plus");
    }

    @Test
    void aProductWithNoWordsOfItsOwnIsDescribedFromItsCategory() {
        assertThat(MerchantFeedService.describe("Nagaraku Premium Mild 041", "Nagaraku Premium Mild 041",
                "LASHES > LASH GLUES AND REMOVERS"))
                .isEqualTo("Nagaraku Premium Mild 041. Lash glues and removers from Trendysh Accosmetic, Nairobi: "
                        + "professional beauty supplies for salons and home use.");
        assertThat(MerchantFeedService.describe("Knipper", null, null))
                .isEqualTo("Knipper. Beauty supplies from Trendysh Accosmetic, Nairobi, for salons and home use.");
        assertThat(MerchantFeedService.describe("Glue", "Strong hold glue", "LASHES")).isEqualTo("Strong hold glue");
    }

    @Test
    void onlyRealGtinsAreSent() {
        assertThat(MerchantFeedService.validGtin("5901234123457")).isTrue(); // EAN-13
        assertThat(MerchantFeedService.validGtin("036000291452")).isTrue(); // UPC-A
        assertThat(MerchantFeedService.validGtin("96385074")).isTrue(); // EAN-8
        assertThat(MerchantFeedService.validGtin("5901234123458")).isFalse(); // wrong check digit
        assertThat(MerchantFeedService.validGtin("NBH-LASH-01")).isFalse(); // a shop's own code
        assertThat(MerchantFeedService.validGtin("0000000000000")).isFalse();
        assertThat(MerchantFeedService.validGtin(null)).isFalse();
    }

    @Test
    void controlCharactersFromPastedTextDoNotBreakTheFeed() throws Exception {
        var i = new MerchantFeedService.Item("X1", "Bad\u0001 title", "line\u0008 one", SITE + "/p/", "https://x/y.jpg", false,
                BigDecimal.TEN, null, null, null, null, null, null, null, null);
        String xml = MerchantFeedService.render(List.of(i), SITE, NOW);
        parse(xml);
        assertThat(xml).contains("<title>Bad title</title>").contains("<g:availability>out_of_stock</g:availability>")
                .doesNotContain("g:brand").doesNotContain("g:product_type");
    }

    @Test
    void theProductTypeIsTheCategoryAndThoseAboveIt() {
        UUID top = UUID.randomUUID(), sub = UUID.randomUUID();
        var cats = Map.of(top, new CatalogCategoryDto(top, "lash", "Lash supplies", null, null),
                sub, new CatalogCategoryDto(sub, "lash-glue", "Lash glue", null, top));
        assertThat(MerchantFeedService.productType(sub, cats)).isEqualTo("Lash supplies > Lash glue");
        assertThat(MerchantFeedService.productType(null, cats)).isNull();
    }

    @Test
    void markupAndMarkdownAreTakenOutOfDescriptions() {
        assertThat(MerchantFeedService.plain("## Why you'll love it\n**Strong** <i>hold</i>&nbsp;all day"))
                .isEqualTo("Why you'll love it\nStrong hold all day");
    }
}
