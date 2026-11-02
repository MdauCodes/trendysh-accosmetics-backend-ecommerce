package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.repository.CustomerOrderRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** A place under a provider has a price, and that fee is paid with the order. Also the payer's name and the next-day list. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PricedDeliveryIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CustomerOrderRepository orders;

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
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private JsonNode okPublic(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode operator(String name) throws Exception {
        for (JsonNode o : okPublic("/api/v1/public/delivery/operators")) {
            if (name.equals(o.get("name").asText())) return o;
        }
        throw new AssertionError("No operator " + name);
    }

    private JsonNode place(String operatorId, String name) throws Exception {
        for (JsonNode p : okPublic("/api/v1/public/delivery/operators/" + operatorId + "/places")) {
            if (name.equals(p.get("name").asText())) return p;
        }
        throw new AssertionError("No place " + name);
    }

    private MvcResult checkout(String method, String address, String transportJson, String expectedFee) throws Exception {
        String body = "{\"name\":\"Priced Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
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
        return mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Test Customer\",\"guestPhone\":\"" + TestContacts.phone() + "\","
                        + "\"fulfilmentType\":\"DELIVERY_COURIER\",\"deliveryAddress\":\"" + address + "\","
                        + "\"deliveryMethod\":\"" + method + "\",\"transport\":" + transportJson
                        + (expectedFee == null ? "" : ",\"expectedDeliveryFee\":" + expectedFee) + "}"))
                .andReturn();
    }

    private static String pricedTransport(JsonNode operator, JsonNode place) {
        return "{\"operatorId\":\"" + operator.get("id").asText() + "\",\"stopId\":\"" + place.get("id").asText() + "\"}";
    }

    @Test
    void providersComeInThreeKindsAndEveryPlaceHasAPrice() throws Exception {
        assertThat(operator("Rider (boda boda)").get("kind").asText()).isEqualTo("RIDER");
        assertThat(operator("Super Metro").get("kind").asText()).isEqualTo("METRO");
        assertThat(operator("2NK").get("kind").asText()).isEqualTo("OUTSIDE");
        for (String name : new String[] {"Meru Nissan", "Unique Shuttle", "Luxury Shuttle", "Kukena"}) {
            assertThat(operator(name).get("kind").asText()).as(name).isEqualTo("OUTSIDE");
        }
        JsonNode places = okPublic("/api/v1/public/delivery/operators/" + operator("Super Metro").get("id").asText() + "/places");
        assertThat(places.size()).isGreaterThan(1);
        places.forEach(p -> assertThat(p.get("fareMin").decimalValue().signum()).isPositive());
    }

    @Test
    void couriersOffTheListAreOnlyReachedByTypingAndHaveNoPrice() throws Exception {
        for (JsonNode o : okPublic("/api/v1/public/delivery/operators")) {
            assertThat(o.get("name").asText().toLowerCase()).doesNotContain("g4s", "wells fargo", "pickup mtaani");
        }
        MvcResult placed = checkout("COURIER", "G4S — Nyeri, near the stage", "null", "0");
        assertThat(placed.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = json.readTree(placed.getResponse().getContentAsString()).get("order");
        assertThat(order.get("deliveryFee").decimalValue()).isEqualByComparingTo("0");
        assertThat(order.get("deliveryCarrier").asText()).isEqualTo("G4S");
    }

    @Test
    void thePlacesPriceIsChargedWithTheOrder() throws Exception {
        JsonNode operator = operator("Easy Coach");
        JsonNode place = place(operator.get("id").asText(), "Kisumu");
        String fee = place.get("fareMin").asText();

        MvcResult placed = checkout("COURIER", "Easy Coach — Kisumu", pricedTransport(operator, place), fee);
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(placed.getResponse().getContentAsString());
        assertThat(body.get("order").get("deliveryFee").decimalValue()).isEqualByComparingTo(fee);
        assertThat(body.get("order").get("grandTotal").decimalValue())
                .isEqualByComparingTo(place.get("fareMin").decimalValue().add(new java.math.BigDecimal(1000)));

        String id = body.get("order").get("id").asText();
        JsonNode terms = okStaff(get("/api/v1/orders/" + id + "/delivery-terms"));
        assertThat(terms.get("payWay").asText()).isEqualTo("PAY_BUSINESS");
        JsonNode detail = okStaff(get("/api/v1/orders/" + id));
        assertThat(detail.get("payments").get(0).get("amount").decimalValue())
                .isEqualByComparingTo(body.get("order").get("grandTotal").decimalValue());
    }

    @Test
    void aPriceChangedSinceTheCustomerSawItIsRefusedNotChargedQuietly() throws Exception {
        JsonNode operator = operator("2NK");
        JsonNode place = place(operator.get("id").asText(), "Nyeri");
        MvcResult refused = checkout("COURIER", "2NK — Nyeri", pricedTransport(operator, place), "1");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aPlaceOfAnotherProviderIsNotPriced() throws Exception {
        JsonNode easyCoach = operator("Easy Coach");
        JsonNode metroPlace = place(operator("Super Metro").get("id").asText(), "Nairobi CBD");
        MvcResult placed = checkout("COURIER", "Somewhere", pricedTransport(easyCoach, metroPlace), null);
        assertThat(placed.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = json.readTree(placed.getResponse().getContentAsString()).get("order");
        assertThat(order.get("deliveryFee").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void anUnlistedPlaceIsPlacedForProductsAndStaffQuoteIt() throws Exception {
        MvcResult placed = checkout("COURIER", "Somebody's bus — far away", "null", "0");
        assertThat(placed.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = json.readTree(placed.getResponse().getContentAsString()).get("order");
        assertThat(order.get("grandTotal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(okStaff(get("/api/v1/orders/" + order.get("id").asText() + "/delivery-terms")).get("agreedAt").isNull()).isTrue();
    }

    @Test
    void aPaidFeeCanOnlyGoUpAndTheDifferenceIsOwed() throws Exception {
        JsonNode operator = operator("Meru Nissan");
        JsonNode place = place(operator.get("id").asText(), "Meru");
        JsonNode body = json.readTree(checkout("COURIER", "Meru Nissan — Meru", pricedTransport(operator, place), place.get("fareMin").asText())
                .getResponse().getContentAsString());
        String id = body.get("order").get("id").asText();
        okStaff(post("/api/v1/payments/" + body.get("paymentRecordId").asText() + "/confirm-manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"PRICED" + UUID.randomUUID().toString().substring(0, 8) + "\",\"method\":\"MPESA\"}"));

        JsonNode higher = okStaff(post("/api/v1/orders/" + id + "/delivery-terms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fee\":\"" + place.get("fareMin").decimalValue().add(new java.math.BigDecimal(100))
                        + "\",\"payWay\":\"PAY_BUSINESS\"}"));
        assertThat(higher.get("owed").decimalValue()).isEqualByComparingTo("100");

        MvcResult lower = mvc.perform(post("/api/v1/orders/" + id + "/delivery-terms")
                .header("Authorization", "Bearer " + staffToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fee\":\"1\",\"payWay\":\"PAY_BUSINESS\"}")).andReturn();
        assertThat(lower.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void aCustomerCanSendThePayersNameInsteadOfACodeAndStaffApproveIt() throws Exception {
        JsonNode body = json.readTree(checkout("COURIER", "x — y", "null", "0").getResponse().getContentAsString());
        String orderNo = body.get("order").get("orderNo").asText();
        String reference = body.get("paymentInstructions").get("reference").asText();
        String id = body.get("order").get("id").asText();

        MvcResult nothing = mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}")).andReturn();
        assertThat(nothing.getResponse().getStatus()).isEqualTo(400);

        MvcResult sent = mvc.perform(post("/api/v1/payments/pay/" + orderNo + "/claim").param("ref", reference)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000,\"payerName\":\"Njeri Kamau\"}")).andReturn();
        assertThat(sent.getResponse().getStatus()).as(sent.getResponse().getContentAsString()).isEqualTo(200);

        JsonNode claim = okStaff(get("/api/v1/orders/" + id)).get("claims").get(0);
        assertThat(claim.get("payerName").asText()).isEqualTo("Njeri Kamau");
        okStaff(post("/api/v1/admin/payments/claims/" + claim.get("id").asText() + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(okStaff(get("/api/v1/orders/" + id)).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void aDeliveryThatLeftBeforeTodayIsOnTheNextDayListUntilStaffAsk() throws Exception {
        JsonNode body = json.readTree(checkout("COURIER", "x — y", "null", "0").getResponse().getContentAsString());
        String id = body.get("order").get("id").asText();
        var order = orders.findById(UUID.fromString(id)).orElseThrow();
        order.setStatus(com.nbh.backend.order.entity.OrderStatus.DISPATCHED);
        order.setDispatchedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        orders.save(order);

        assertThat(okStaff(get("/api/v1/orders/received-check")).findValuesAsText("id")).contains(id);
        okStaff(post("/api/v1/orders/" + id + "/received-check-sent"));
        assertThat(okStaff(get("/api/v1/orders/received-check")).findValuesAsText("id")).doesNotContain(id);
    }
}
