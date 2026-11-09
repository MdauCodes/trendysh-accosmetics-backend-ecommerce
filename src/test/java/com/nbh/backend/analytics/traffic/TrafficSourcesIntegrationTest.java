package com.nbh.backend.analytics.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Where shoppers come from (owner, 2026-11-09): a visit is kept with its channel, an order placed in it carries that
 * channel (last touch) and the visitor's first one (first touch), bots are not counted, and the report puts ad spend
 * next to the paid channels.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrafficSourcesIntegrationTest {

    private static final String BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private JdbcTemplate jdbc;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, boolean staff) throws Exception {
        if (staff) request.header("Authorization", "Bearer " + staffToken);
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 204);
        String body = r.getResponse().getContentAsString();
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private String visit(UUID visitor, String channel, String source, String userAgent) throws Exception {
        JsonNode r = ok(post("/api/v1/public/visits").header("User-Agent", userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"visitor":"%s","channel":"%s","source":"%s","campaign":"Launch","landing":"/shop/lashes/?gclid=abc","device":"phone"}
                        """.formatted(visitor, channel, source)), false);
        return r.get("id").isNull() ? null : r.get("id").asText();
    }

    private String newProduct() throws Exception {
        String body = "{\"name\":\"Source test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1500\",\"isActive\":true}]}";
        JsonNode product = ok(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body), true);
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"), true);
        ok(post("/api/v1/products/" + productId + "/publish"), true);
        return variantId;
    }

    private String checkout(String visitId) throws Exception {
        String session = UUID.randomUUID().toString();
        ok(post("/api/v1/cart/items").header("X-Cart-Session", session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + newProduct() + "\",\"qty\":1}"), false);
        var request = post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"Achieng Otieno","guestEmail":"s-%s@example.com","guestPhone":"07%08d","fulfilmentType":"PICKUP"}
                        """.formatted(UUID.randomUUID().toString().substring(0, 8), ThreadLocalRandom.current().nextInt(100_000_000)));
        if (visitId != null) request.header("X-Visit-Id", visitId);
        return ok(request, false).get("order").get("id").asText();
    }

    @Test
    void anOrderCarriesTheVisitItWasPlacedInAndTheVisitorsFirstOne() throws Exception {
        UUID visitor = UUID.randomUUID();
        visit(visitor, "SOCIAL", "Instagram", BROWSER);
        String adsVisit = visit(visitor, "GOOGLE_ADS", "google", BROWSER);

        String orderId = checkout(adsVisit);

        var row = jdbc.queryForMap("SELECT visit_id, first_channel, first_source, last_channel, last_source, last_campaign "
                + "FROM customer_order WHERE id = ?", UUID.fromString(orderId));
        assertThat(row.get("visit_id")).hasToString(adsVisit);
        assertThat(row.get("last_channel")).isEqualTo("GOOGLE_ADS");
        assertThat(row.get("last_campaign")).isEqualTo("launch");
        assertThat(row.get("first_channel")).isEqualTo("SOCIAL");
        assertThat(row.get("first_source")).isEqualTo("instagram");
        String landing = jdbc.queryForObject("SELECT landing FROM site_visit WHERE id = ?", String.class, UUID.fromString(adsVisit));
        assertThat(landing).isEqualTo("/shop/lashes/");
    }

    @Test
    void anOrderWithoutAVisitStillGoesThroughAndHasNoSource() throws Exception {
        String orderId = checkout(null);
        String unknown = checkout(UUID.randomUUID().toString());
        for (String id : new String[] {orderId, unknown}) {
            assertThat(jdbc.queryForObject("SELECT last_channel FROM customer_order WHERE id = ?", String.class,
                    UUID.fromString(id))).isNull();
        }
    }

    @Test
    void botsAndUnknownChannelsAreNotKept() throws Exception {
        assertThat(visit(UUID.randomUUID(), "DIRECT", "", "WhatsApp/2.23.20.0")).isNull();
        assertThat(visit(UUID.randomUUID(), "DIRECT", "", "Googlebot/2.1 (+http://www.google.com/bot.html)")).isNull();
        assertThat(visit(UUID.randomUUID(), "MADE_UP", "", BROWSER)).isNull();
        assertThat(visit(UUID.randomUUID(), "DIRECT", "", BROWSER)).isNotNull();
    }

    @Test
    void theReportPutsVisitsOrdersBasketsAndSpendTogether() throws Exception {
        UUID visitor = UUID.randomUUID();
        String v = visit(visitor, "GOOGLE_ADS", "google-report-test", BROWSER);
        ok(post("/api/v1/public/visits/" + v + "/events").header("User-Agent", BROWSER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"WHATSAPP_BASKET\",\"value\":2400}"), false);
        ok(post("/api/v1/public/visits/" + v + "/events").header("User-Agent", BROWSER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"ADD_TO_CART\"}"), false);
        checkout(v);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));
        ok(put("/api/v1/admin/analytics/spend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"day\":\"" + today + "\",\"channel\":\"GOOGLE_ADS\",\"amountKes\":1300,\"note\":\"test\"}"), true);

        JsonNode report = ok(get("/api/v1/admin/analytics/sources?from=" + today + "&to=" + today), true);
        JsonNode source = null;
        for (JsonNode s : report.get("sources")) {
            if ("google-report-test".equals(s.path("source").asText())) source = s;
        }
        assertThat(source).isNotNull();
        assertThat(source.get("visits").asLong()).isEqualTo(1);
        assertThat(source.get("whatsappBaskets").asLong()).isEqualTo(1);
        assertThat(source.get("whatsappBasketValue").decimalValue()).isEqualByComparingTo("2400");
        assertThat(source.get("addToCarts").asLong()).isEqualTo(1);
        assertThat(source.get("orders").asLong()).isEqualTo(1);
        JsonNode ads = null;
        for (JsonNode c : report.get("channels")) {
            if ("GOOGLE_ADS".equals(c.get("channel").asText())) ads = c;
        }
        assertThat(ads).isNotNull();
        assertThat(ads.get("spend").decimalValue()).isEqualByComparingTo("1300");
        assertThat(report.get("days")).hasSize(1);

        // Zero removes the day's amount; spend is only for paid channels.
        ok(put("/api/v1/admin/analytics/spend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"day\":\"" + today + "\",\"channel\":\"GOOGLE_ADS\",\"amountKes\":0}"), true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ad_spend WHERE day = ?", Long.class, today)).isZero();
        var refused = put("/api/v1/admin/analytics/spend").header("Authorization", "Bearer " + staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"day\":\"" + today + "\",\"channel\":\"DIRECT\",\"amountKes\":10}");
        assertThat(mvc.perform(refused).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void theReportIsForStaffOnly() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));
        int status = mvc.perform(get("/api/v1/admin/analytics/sources?from=" + today + "&to=" + today))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }
}
