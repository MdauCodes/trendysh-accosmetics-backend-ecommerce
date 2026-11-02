package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.finance.service.FinanceService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The delivery fee agreed on WhatsApp and how it gets paid: only money paid to the shop changes what the order owes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeliveryTermsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private FinanceService finance;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode okStaff(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + staffToken)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A real order for KSh 1,000 of products, placed with no email, and its pay reference. */
    private JsonNode placeOrder() throws Exception {
        String body = "{\"name\":\"Terms Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}";
        JsonNode product = okStaff(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON).content(body));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        okStaff(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        okStaff(post("/api/v1/products/" + productId + "/publish"));
        String session = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":1}")).andReturn();
        MvcResult placed = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Test Customer\",\"guestPhone\":\"" + TestContacts.phone() + "\","
                        + "\"fulfilmentType\":\"DELIVERY_COURIER\",\"deliveryAddress\":\"2NK — Nyeri\",\"deliveryMethod\":\"COURIER\"}"))
                .andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(placed.getResponse().getContentAsString());
    }

    private String idOf(JsonNode placed) {
        return placed.get("order").get("id").asText();
    }

    private void markPaid(JsonNode placed) throws Exception {
        okStaff(post("/api/v1/payments/" + placed.get("paymentRecordId").asText() + "/confirm-manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"TERMS" + UUID.randomUUID().toString().substring(0, 8) + "\",\"method\":\"MPESA\"}"));
    }

    private JsonNode agree(String orderId, String fee, String way, String till) throws Exception {
        String tillJson = till == null ? "null" : "\"" + till + "\"";
        return okStaff(post("/api/v1/orders/" + orderId + "/delivery-terms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fee\":\"" + fee + "\",\"payWay\":\"" + way + "\",\"till\":" + tillJson + "}"));
    }

    private JsonNode order(String orderId) throws Exception {
        return okStaff(get("/api/v1/orders/" + orderId));
    }

    private static BigDecimal money(JsonNode node) {
        return node.decimalValue().stripTrailingZeros();
    }

    @Test
    void payingTheProviderLeavesTheOrderTotalAlone() throws Exception {
        String id = idOf(placeOrder());

        JsonNode terms = agree(id, "350", "ON_ARRIVAL", null);
        assertThat(terms.get("payWay").asText()).isEqualTo("ON_ARRIVAL");
        assertThat(money(terms.get("fee"))).isEqualByComparingTo("350");
        assertThat(money(terms.get("owed"))).isEqualByComparingTo("0");
        assertThat(terms.get("payRef").isNull()).isFalse();
        assertThat(money(order(id).get("grandTotal"))).isEqualByComparingTo("1000");
        assertThat(money(order(id).get("agreedDeliveryPrice"))).isEqualByComparingTo("350");
    }

    @Test
    void aTillIsNeededToPayATill() throws Exception {
        String id = idOf(placeOrder());
        MvcResult refused = mvc.perform(post("/api/v1/orders/" + id + "/delivery-terms")
                .header("Authorization", "Bearer " + staffToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fee\":\"350\",\"payWay\":\"PROVIDER_TILL\",\"till\":\" \"}")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);

        assertThat(agree(id, "350", "PROVIDER_TILL", "123456").get("till").asText()).isEqualTo("123456");
    }

    @Test
    void payingTheShopBeforeTheOrderIsPaidPutsTheFeeInWhatThePayPageAsksFor() throws Exception {
        JsonNode placed = placeOrder();
        String id = idOf(placed);

        JsonNode terms = agree(id, "350", "PAY_BUSINESS", null);
        assertThat(money(terms.get("owed"))).isEqualByComparingTo("0");
        JsonNode detail = order(id);
        assertThat(money(detail.get("grandTotal"))).isEqualByComparingTo("1350");
        assertThat(money(detail.get("payments").get(0).get("amount"))).isEqualByComparingTo("1350");

        // Changing to paying the provider takes it back out.
        agree(id, "400", "ON_ARRIVAL", null);
        detail = order(id);
        assertThat(money(detail.get("grandTotal"))).isEqualByComparingTo("1000");
        assertThat(money(detail.get("payments").get(0).get("amount"))).isEqualByComparingTo("1000");

        // Paid in full with the fee, nothing is left to chase and the fee is settled.
        agree(id, "350", "PAY_BUSINESS", null);
        markPaid(placed);
        assertThat(money(okStaff(get("/api/v1/orders/" + id + "/delivery-terms")).get("owed"))).isEqualByComparingTo("0");
        MvcResult refused = mvc.perform(post("/api/v1/orders/" + id + "/delivery-terms")
                .header("Authorization", "Bearer " + staffToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fee\":\"10\",\"payWay\":\"ON_ARRIVAL\"}")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void anOrderAlreadyPaidOwesTheFeeUntilStaffRecordIt() throws Exception {
        JsonNode placed = placeOrder();
        String id = idOf(placed);
        markPaid(placed);

        JsonNode terms = agree(id, "350", "PAY_BUSINESS", null);
        assertThat(money(terms.get("owed"))).isEqualByComparingTo("350");
        assertThat(terms.get("payRef").isNull()).isTrue();
        assertThat(money(order(id).get("grandTotal"))).isEqualByComparingTo("1350");
        assertThat(finance.trail(UUID.fromString(id)).stillOwed()).isEqualByComparingTo("350");

        JsonNode after = okStaff(post("/api/v1/orders/" + id + "/delivery-money").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"Q" + UUID.randomUUID().toString().substring(0, 8) + "\"}"));
        assertThat(money(after.get("owed"))).isEqualByComparingTo("0");
        assertThat(finance.trail(UUID.fromString(id)).stillOwed()).isEqualByComparingTo("0");
        assertThat(finance.trail(UUID.fromString(id)).received()).isEqualByComparingTo("1350");
        assertThat(order(id).get("status").asText()).isEqualTo("PAID");

        MvcResult twice = mvc.perform(post("/api/v1/orders/" + id + "/delivery-money")
                .header("Authorization", "Bearer " + staffToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"qgh12abc\"}")).andReturn();
        assertThat(twice.getResponse().getStatus()).isEqualTo(422);
    }
}
