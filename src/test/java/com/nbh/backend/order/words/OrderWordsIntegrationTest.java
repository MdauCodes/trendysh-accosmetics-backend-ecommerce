package com.nbh.backend.order.words;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.security.JwtService;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.junit.jupiter.api.Assumptions;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The word list behind the paste box: the starter words, staff changes, and suggestions learned from corrections. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderWordsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwtService;
    @Autowired private StaffUserRepository staffUsers;
    @Autowired private ProductVariantRepository variants;

    private String token;

    @BeforeEach
    void signIn() {
        var admin = staffUsers.findAll().stream()
                .filter(u -> u.getStaffRole() != null && "SUPER_ADMIN".equals(u.getStaffRole().getName()))
                .findFirst().orElseThrow();
        token = jwtService.generateAccessToken(admin);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private JsonNode list() throws Exception {
        return json.readTree(call(get("/api/v1/admin/order-words"), null).getResponse().getContentAsString());
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    void theListStartsWithFillerWordsAndSpellings() throws Exception {
        JsonNode all = list();
        boolean naomba = false, glu = false;
        for (JsonNode w : all) {
            if ("IGNORE".equals(w.get("kind").asText()) && "naomba".equals(w.get("phrase").asText())) naomba = true;
            if ("SAME_AS".equals(w.get("kind").asText()) && "glu".equals(w.get("phrase").asText())) {
                glu = "glue".equals(w.get("meaning").asText());
            }
        }
        assertThat(naomba).as("Swahili filler word").isTrue();
        assertThat(glu).as("a spelling that means glue").isTrue();
    }

    @Test
    void staffAddChangeAndRemoveWordsAndAWordIsNeverListedTwice() throws Exception {
        String phrase = unique("gundii");
        MvcResult added = call(post("/api/v1/admin/order-words"),
                "{\"kind\":\"SAME_AS\",\"phrase\":\"" + phrase + "\",\"meaning\":\"glue\"}");
        assertThat(added.getResponse().getStatus()).isEqualTo(201);
        String id = json.readTree(added.getResponse().getContentAsString()).get("id").asText();

        MvcResult again = call(post("/api/v1/admin/order-words"),
                "{\"kind\":\"SAME_AS\",\"phrase\":\"" + phrase.toUpperCase() + "\",\"meaning\":\"glue\"}");
        assertThat(again.getResponse().getStatus()).isEqualTo(422);

        MvcResult noMeaning = call(post("/api/v1/admin/order-words"),
                "{\"kind\":\"SAME_AS\",\"phrase\":\"" + unique("x") + "\"}");
        assertThat(noMeaning.getResponse().getStatus()).isEqualTo(422);

        MvcResult changed = call(put("/api/v1/admin/order-words/" + id),
                "{\"kind\":\"IGNORE\",\"phrase\":\"" + phrase + "\"}");
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(changed.getResponse().getContentAsString()).get("kind").asText()).isEqualTo("IGNORE");

        assertThat(call(delete("/api/v1/admin/order-words/" + id), null).getResponse().getStatus()).isEqualTo(204);
        assertThat(list().findValues("id").stream().map(JsonNode::asText)).doesNotContain(id);
    }

    @Test
    void aCorrectionBecomesASuggestedNicknameOnceAndCountsAfterApproval() throws Exception {
        var variant = variants.findAll().stream().filter(v -> !v.isDeleted()).findFirst();
        Assumptions.assumeTrue(variant.isPresent(), "needs a product in the test database");
        String phrase = unique("ruby gel ");
        String body = "[{\"phrase\":\"" + phrase + "\",\"variantId\":\"" + variant.get().getId() + "\"}]";

        assertThat(call(post("/api/v1/admin/order-words/suggestions"), body).getResponse().getStatus()).isEqualTo(204);
        assertThat(call(post("/api/v1/admin/order-words/suggestions"), body).getResponse().getStatus()).isEqualTo(204);

        JsonNode found = null;
        int count = 0;
        for (JsonNode w : list()) {
            if (phrase.equals(w.get("phrase").asText())) {
                found = w;
                count++;
            }
        }
        assertThat(count).as("suggested once, however often it is corrected").isEqualTo(1);
        assertThat(found.get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(found.get("kind").asText()).isEqualTo("NICKNAME");
        assertThat(found.get("productLabel").asText()).isNotBlank();

        MvcResult approved = call(post("/api/v1/admin/order-words/" + found.get("id").asText() + "/approve"), null);
        assertThat(json.readTree(approved.getResponse().getContentAsString()).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void onlySignedInStaffSeeTheList() throws Exception {
        assertThat(mvc.perform(get("/api/v1/admin/order-words")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }
}
