package com.nbh.backend.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.order.service.TestOrderClearService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import jakarta.persistence.EntityManager;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Clearing test orders leaves nothing of them, puts their stock back, and cannot touch an order placed afterwards. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestOrderClearIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private TestOrderClearService service;
    @Autowired private EntityManager em;

    private String staffToken;

    @BeforeEach
    void setUp() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        staffToken = jwtService.generateAccessToken(admin);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + staffToken)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private String[] newProduct() throws Exception {
        JsonNode product = ok(post("/api/v1/products/draft").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Clear Test " + UUID.randomUUID().toString().substring(0, 8) + "\",\"categoryIds\":[],"
                        + "\"published\":false,\"variants\":[{\"variantName\":\"Default\",\"sellingPrice\":\"1000\",\"isActive\":true}]}"));
        String productId = product.get("id").asText();
        String variantId = product.get("variants").get(0).get("id").asText();
        ok(put("/api/v1/products/" + productId + "/variants/" + variantId + "/stock")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"unitCost\":100}"));
        ok(post("/api/v1/products/" + productId + "/publish"));
        return new String[] {productId, variantId};
    }

    private JsonNode place(String variantId) throws Exception {
        String session = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/cart/items").header("X-Cart-Session", session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":\"" + variantId + "\",\"qty\":2}")).andReturn();
        MvcResult placed = mvc.perform(post("/api/v1/checkout").header("X-Cart-Session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"Test Customer\",\"guestPhone\":\"" + TestContacts.phone() + "\","
                        + "\"fulfilmentType\":\"DELIVERY_COURIER\",\"deliveryAddress\":\"x — y\",\"deliveryMethod\":\"COURIER\"}"))
                .andReturn();
        assertThat(placed.getResponse().getStatus()).as(placed.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(placed.getResponse().getContentAsString());
    }

    private BigDecimal onHand(String variantId) {
        return (BigDecimal) em.createNativeQuery(
                "select coalesce(sum(on_hand_qty),0) from stock_balance where variant_id = :v and account = 'SHOP'")
                .setParameter("v", UUID.fromString(variantId)).getSingleResult();
    }

    private long rows(String table, String column, UUID id) {
        return ((Number) em.createNativeQuery("select count(*) from " + table + " where " + column + " = :id")
                .setParameter("id", id).getSingleResult()).longValue();
    }

    @Test
    void aDispatchedOrderGoesEntirelyAndItsStockComesBack() throws Exception {
        String variantId = newProduct()[1];
        BigDecimal before = onHand(variantId);
        JsonNode placed = place(variantId);
        UUID id = UUID.fromString(placed.get("order").get("id").asText());
        ok(post("/api/v1/payments/" + placed.get("paymentRecordId").asText() + "/confirm-manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"CLEAR" + UUID.randomUUID().toString().substring(0, 8) + "\",\"method\":\"MPESA\"}"));
        ok(post("/api/v1/orders/" + id + "/assign-transporter").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transporterName\":\"T\",\"transporterPhone\":\"0720000111\",\"agreedPrice\":\"100\"}"));
        ok(post("/api/v1/orders/" + id + "/fulfil"));
        assertThat(onHand(variantId)).isEqualByComparingTo(before.subtract(new BigDecimal(2)));

        TestOrderClearService.Cleared cleared = service.clear(List.of(id));

        assertThat(cleared.orders()).isEqualTo(1);
        assertThat(cleared.stockReturned()).isEqualTo(1);
        assertThat(orders.findById(id)).isEmpty();
        assertThat(rows("customer_order_item", "order_id", id)).isZero();
        assertThat(rows("payment_record", "order_id", id)).isZero();
        assertThat(rows("product_sale_event", "order_id", id)).isZero();
        assertThat(onHand(variantId)).isEqualByComparingTo(before);
    }

    @Test
    void anUnpaidOrderGoesAndItsHeldStockIsFreed() throws Exception {
        String variantId = newProduct()[1];
        UUID id = UUID.fromString(place(variantId).get("order").get("id").asText());

        service.clear(List.of(id));

        assertThat(orders.findById(id)).isEmpty();
        Number held = (Number) em.createNativeQuery(
                "select coalesce(sum(reserved_qty),0) from stock_balance where variant_id = :v and account = 'SHOP'")
                .setParameter("v", UUID.fromString(variantId)).getSingleResult();
        assertThat(held.intValue()).isZero();
    }

    @Test
    void anOrderPlacedAfterTheCutoffIsNeverOnTheList() throws Exception {
        String variantId = newProduct()[1];
        String id = place(variantId).get("order").get("id").asText();
        assertThat(service.waiting()).noneMatch(o -> o.id().toString().equals(id));
    }

    @Test
    void itIsForTheDeveloperOnly() throws Exception {
        MvcResult owner = mvc.perform(post("/api/v1/admin/mock-data/test-orders/clear").header("Authorization", "Bearer " + staffToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"CLEAR\"}")).andReturn();
        assertThat(owner.getResponse().getStatus()).isEqualTo(403);
    }
}
