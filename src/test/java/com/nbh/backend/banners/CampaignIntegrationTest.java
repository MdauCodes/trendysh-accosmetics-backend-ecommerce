package com.nbh.backend.banners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Campaigns (owner, 2026-11-04) end to end: a banner with deals, approved, reaches the shop with its deal cards; a deal's
 * price is what cards and the basket charge; "buy 5 get 3 free" adds the free pieces on top and lets the shopper pick
 * their shades; a product can't be in two campaigns at once; and the wholesale switch turns tiers off everywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampaignIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CampaignDealService campaigns;

    private String token;

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    @AfterEach
    void wholesaleBackOn() throws Exception {
        call(put("/api/v1/admin/wholesale"), "{\"wholesaleOn\":true}", true);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body, boolean staff) throws Exception {
        if (staff) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body, boolean staff) throws Exception {
        MvcResult r = call(request, body, staff);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        String content = r.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    private record Made(String productId, String[] variantIds) {}

    /** A published product with these options' prices, each with {@code stock} on the shelf. */
    private Made product(int stock, String... prices) throws Exception {
        StringBuilder variants = new StringBuilder();
        for (int i = 0; i < prices.length; i++) {
            if (i > 0) variants.append(',');
            variants.append("{\"variantName\":\"Shade ").append(i + 1).append("\",\"sellingPrice\":\"").append(prices[i])
                    .append("\",\"isActive\":true}");
        }
        JsonNode p = ok(post("/api/v1/products/draft"), "{\"name\":\"Campaign " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"categoryIds\":[],\"published\":false,\"variants\":[" + variants + "]}", true);
        String productId = p.get("id").asText();
        String[] ids = new String[prices.length];
        for (int i = 0; i < prices.length; i++) {
            ids[i] = p.get("variants").get(i).get("id").asText();
            ok(put("/api/v1/products/" + productId + "/variants/" + ids[i] + "/stock"),
                    "{\"quantity\":" + stock + ",\"unitCost\":50}", true);
        }
        ok(post("/api/v1/products/" + productId + "/publish"), null, true);
        return new Made(productId, ids);
    }

    /** A hero banner with a picture and a headline, its campaign saved with these deals (JSON array), then approved. */
    private JsonNode campaign(String title, String dealsJson, boolean approve) throws Exception {
        JsonNode b = ok(post("/api/v1/admin/promo-banners"), "{\"placement\":\"HERO\",\"name\":\"" + title + " hero\"}", true);
        String id = b.get("id").asText();
        jdbc.update("UPDATE promo_banner SET image_url = 'https://res.cloudinary.com/demo/x.png', headline = 'Look!',"
                + " ends_at = NULL WHERE id = ?", UUID.fromString(id));
        JsonNode saved = ok(put("/api/v1/admin/promo-banners/" + id + "/campaign"),
                "{\"title\":\"" + title + "\",\"deals\":" + dealsJson + "}", true);
        if (approve) saved = ok(post("/api/v1/admin/promo-banners/" + id + "/approve"), null, true);
        campaigns.forget();
        return saved;
    }

    private JsonNode addToCart(String session, String variantId, int qty) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":\"" + variantId + "\",\"qty\":" + qty + "}"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static JsonNode lineOf(JsonNode cart, String variantId, boolean free) {
        for (JsonNode i : cart.get("items")) {
            if (i.get("variantId").asText().equals(variantId) && (i.get("freeDealId").isNull() != free)) return i;
        }
        return null;
    }

    @Test
    void aCampaignsDealsAreItsCardsAndWhatTheShopCharges() throws Exception {
        Made lamp = product(10, "2500");
        JsonNode admin = campaign("Weekend " + UUID.randomUUID().toString().substring(0, 5),
                "[{\"productId\":\"" + lamp.productId() + "\",\"type\":\"AMOUNT\",\"amountOff\":500}]", true);
        assertThat(admin.path("state").asText()).isEqualTo("LIVE");
        String slug = admin.path("campaign").path("slug").asText();
        assertThat(slug).startsWith("weekend-");
        assertThat(admin.path("campaign").path("deals").get(0).path("state").asText()).isEqualTo("LIVE");

        JsonNode shop = ok(get("/api/v1/catalog/campaigns/" + slug), null, false);
        JsonNode card = shop.path("campaign").path("deals").get(0);
        assertThat(card.path("type").asText()).isEqualTo("AMOUNT");
        assertThat(card.path("dealPrice").decimalValue()).isEqualByComparingTo("2000");
        assertThat(card.path("price").decimalValue()).isEqualByComparingTo("2500");

        JsonNode detail = ok(get("/api/v1/catalog/variants/" + lamp.variantIds()[0]), null, false).path("card");
        assertThat(detail.path("price").decimalValue()).isEqualByComparingTo("2000");
        assertThat(detail.path("deal").path("campaignSlug").asText()).isEqualTo(slug);

        String session = UUID.randomUUID().toString();
        JsonNode cart = addToCart(session, lamp.variantIds()[0], 1);
        assertThat(cart.get("subtotal").decimalValue()).isEqualByComparingTo("2000");

        // the same product can't be in a second campaign at the same time
        JsonNode other = campaign("Other " + UUID.randomUUID().toString().substring(0, 5),
                "[{\"productId\":\"" + lamp.productId() + "\",\"type\":\"PERCENT\",\"percentOff\":10}]", false);
        MvcResult clash = call(post("/api/v1/admin/promo-banners/" + other.get("id").asText() + "/approve"), null, true);
        assertThat(clash.getResponse().getStatus()).isEqualTo(409);
        assertThat(clash.getResponse().getContentAsString()).contains("is already in");
    }

    @Test
    void buyFiveGetThreeAddsTheFreePiecesOnTopAndTheShopperPicksTheirShades() throws Exception {
        Made gel = product(20, "350", "350");
        String pink = gel.variantIds()[0];
        String red = gel.variantIds()[1];
        campaign("Gel " + UUID.randomUUID().toString().substring(0, 5),
                "[{\"productId\":\"" + gel.productId() + "\",\"type\":\"BUY_GET\",\"buyQty\":5,\"freeQty\":3}]", true);

        String session = UUID.randomUUID().toString();
        JsonNode four = addToCart(session, pink, 4);
        assertThat(four.get("items")).hasSize(1);
        assertThat(four.get("freeAdded")).isEmpty();

        JsonNode five = addToCart(session, pink, 1);
        JsonNode free = lineOf(five, pink, true);
        assertThat(free).isNotNull();
        assertThat(free.get("qty").decimalValue()).isEqualByComparingTo("3");
        assertThat(free.get("lineTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(five.get("subtotal").decimalValue()).isEqualByComparingTo("1750");
        assertThat(five.get("freeAdded").get(0).get("qty").decimalValue()).isEqualByComparingTo("3");
        String dealId = free.get("freeDealId").asText();

        // the shopper picks red for two of the free pieces
        MvcResult r = mvc.perform(put("/api/v1/cart/free/" + dealId).header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"" + pink + "\":1,\"" + red + "\":2}")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode picked = json.readTree(r.getResponse().getContentAsString());
        assertThat(lineOf(picked, red, true).get("qty").decimalValue()).isEqualByComparingTo("2");
        assertThat(lineOf(picked, pink, true).get("qty").decimalValue()).isEqualByComparingTo("1");

        // a free line can't be changed or removed on its own
        String freeLineId = lineOf(picked, red, true).get("id").asText();
        assertThat(mvc.perform(delete("/api/v1/cart/items/" + freeLineId).header("X-Cart-Session", session))
                .andReturn().getResponse().getStatus()).isEqualTo(422);

        // paying for fewer than five takes the free pieces away
        String paidLine = lineOf(picked, pink, false).get("id").asText();
        MvcResult less = mvc.perform(put("/api/v1/cart/items/" + paidLine).header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":4}")).andReturn();
        JsonNode after = json.readTree(less.getResponse().getContentAsString());
        assertThat(after.get("items")).hasSize(1);
        assertThat(after.get("subtotal").decimalValue()).isEqualByComparingTo("1400");

        // back to five, then checkout: the free pieces are on the order, priced nil and counted under the deal
        mvc.perform(put("/api/v1/cart/items/" + paidLine).header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"qty\":5}")).andReturn();
        String body = """
                {"customerName":"Wanjiku Mwangi","guestEmail":"t-%s@example.com","guestPhone":"07%08d",
                 "fulfilmentType":"PICKUP"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), ThreadLocalRandom.current().nextInt(100_000_000));
        MvcResult order = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(order.getResponse().getStatus()).as(order.getResponse().getContentAsString()).isIn(200, 201);
        Integer freeRows = jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0)::int FROM customer_order_item"
                + " WHERE campaign_deal_id = ?::uuid AND free_under_deal AND unit_price = 0", Integer.class, dealId);
        Integer paidRows = jdbc.queryForObject("SELECT COALESCE(SUM(qty), 0)::int FROM customer_order_item"
                + " WHERE campaign_deal_id = ?::uuid AND NOT free_under_deal", Integer.class, dealId);
        assertThat(freeRows).isEqualTo(3);
        assertThat(paidRows).isEqualTo(5);
    }

    @Test
    void theWholesaleSwitchTurnsTiersOffEverywhereAndBack() throws Exception {
        Made bottle = product(20, "200");
        String v = bottle.variantIds()[0];
        ok(put("/api/v1/products/" + bottle.productId() + "/variants/" + v + "/wholesale"),
                "{\"minQty\":6,\"wholesalePrice\":\"150\"}", true);

        String session = UUID.randomUUID().toString();
        assertThat(addToCart(session, v, 6).get("subtotal").decimalValue()).isEqualByComparingTo("900");

        ok(put("/api/v1/admin/wholesale"), "{\"wholesaleOn\":false}", true);
        JsonNode off = ok(get("/api/v1/cart").header("X-Cart-Session", session), null, false);
        assertThat(off.get("subtotal").decimalValue()).isEqualByComparingTo("1200");
        assertThat(off.get("items").get(0).get("wholesaleMinQty").isNull()).isTrue();
        assertThat(ok(get("/api/v1/catalog/variants/" + v), null, false).path("card").path("wholesalePrice").isNull()).isTrue();
        assertThat(ok(get("/api/v1/catalog/shop-mode"), null, false).path("wholesaleOn").asBoolean()).isFalse();

        ok(put("/api/v1/admin/wholesale"), "{\"wholesaleOn\":true}", true);
        JsonNode on = ok(get("/api/v1/cart").header("X-Cart-Session", session), null, false);
        assertThat(on.get("subtotal").decimalValue()).isEqualByComparingTo("900");
    }
}
