package app.springbootcrm.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the Customer catalog.
 *
 * <ul>
 *   <li>{@code code} is a free string of up to 50 characters, or generated from the
 *       {@code @Reference(prefix = "CUS", codeWidth = 9)} declaration when left empty;</li>
 *   <li>the error shape is the unified {@code ErrorEnvelope}: a {@code message} in the
 *       body and per-field errors under {@code fieldErrors[].field/message}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomersIT {

    @Autowired private TestRestTemplate http;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();

    private String adminToken;

    @BeforeAll
    void login() throws Exception {
        ResponseEntity<String> r = http.postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(json.writeValueAsString(Map.of("username", "admin", "password", "admin")),
                        contentTypeJson()),
                String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        adminToken = json.readTree(r.getBody()).get("token").asText();
    }

    @Test
    @DisplayName("Anonymous GET -> 401")
    void anonymous_get_returns_401() {
        ResponseEntity<String> r = http.exchange(
                url("/api/customers"), HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Admin POST with valid data -> 201")
    void admin_create_valid() throws Exception {
        String uniqueCode = "CUST-" + UUID.randomUUID();
        ResponseEntity<String> r = postJson("/api/customers",
                Map.of("code", uniqueCode, "name", "Globex Industries",
                        "email", "ops@globex.example", "phone", "+1 617 555 0114",
                        "city", "Boston", "notes", "Introduced by a partner"),
                adminToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.get("code").asText()).isEqualTo(uniqueCode);
        assertThat(body.get("name").asText()).isEqualTo("Globex Industries");
        assertThat(body.get("email").asText()).isEqualTo("ops@globex.example");
        assertThat(body.get("city").asText()).isEqualTo("Boston");
        assertThat(body.get("recordDate").asText()).matches("\\d{2}\\.\\d{2}\\.\\d{4}");
        assertThat(body.get("authorUsername")).isNotNull();
    }

    @Test
    @DisplayName("POST without a code -> the code is generated from the @Reference prefix")
    void admin_create_without_code_autogenerates() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Autocode Ltd " + UUID.randomUUID());
        ResponseEntity<String> r = postJson("/api/customers", body, adminToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String generatedCode = json.readTree(r.getBody()).get("code").asText();
        assertThat(generatedCode).startsWith("CUS");      // prefix from @Reference
        assertThat(generatedCode.length()).isEqualTo(9);  // codeWidth = 9
    }

    @Test
    @DisplayName("NAME with disallowed characters (emoji) -> 400 with a field error")
    void name_with_invalid_chars_returns_400() throws Exception {
        ResponseEntity<String> r = postJson("/api/customers",
                Map.of("code", "INV-" + UUID.randomUUID(), "name", "Bad name 😀"),
                adminToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.get("kind").asText()).isEqualTo("VALIDATION");
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("name");
    }

    @Test
    @DisplayName("Malformed EMAIL -> 400 with a field error")
    void invalid_email_returns_400() throws Exception {
        ResponseEntity<String> r = postJson("/api/customers",
                Map.of("code", "EM-" + UUID.randomUUID(), "name", "No At Sign",
                        "email", "not-an-email"),
                adminToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.get("kind").asText()).isEqualTo("VALIDATION");
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("email");
    }

    @Test
    @DisplayName("Duplicate CODE -> 400")
    void duplicate_code_returns_400() throws Exception {
        String dup = "DUP-" + UUID.randomUUID();
        postJson("/api/customers", Map.of("code", dup, "name", "First"), adminToken);
        ResponseEntity<String> r = postJson("/api/customers",
                Map.of("code", dup, "name", "Second"), adminToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.get("kind").asText()).isEqualTo("VALIDATION");
        assertThat(body.get("message").asText()).contains("already exists");
    }

    @Test
    @DisplayName("PUT changes the name; CODE cannot be changed -> 400")
    void put_changes_name_but_not_code() throws Exception {
        String code = "PUT-" + UUID.randomUUID();
        ResponseEntity<String> rc = postJson("/api/customers",
                Map.of("code", code, "name", "Old name"), adminToken);
        assertThat(rc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String customerId = json.readTree(rc.getBody()).get("id").asText();

        ResponseEntity<String> ru = putJson("/api/customers/" + customerId,
                Map.of("code", code, "name", "New name"), adminToken);
        assertThat(ru.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(json.readTree(ru.getBody()).get("name").asText()).isEqualTo("New name");

        ResponseEntity<String> rb = putJson("/api/customers/" + customerId,
                Map.of("code", code + "-NEW", "name", "New name"), adminToken);
        assertThat(rb.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(rb.getBody()).get("message").asText())
                .contains("cannot be changed");
    }

    @Test
    @DisplayName("The list is sorted by CODE ascending")
    void list_is_sorted_by_code() throws Exception {
        ResponseEntity<String> r = http.exchange(url("/api/customers"),
                HttpMethod.GET, authEntity(adminToken), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode arr = json.readTree(r.getBody());
        if (arr.size() >= 2) {
            String prev = arr.get(0).get("code").asText();
            for (int i = 1; i < arr.size(); i++) {
                String cur = arr.get(i).get("code").asText();
                assertThat(cur.compareTo(prev)).isGreaterThanOrEqualTo(0);
                prev = cur;
            }
        }
    }

    // -------- helpers --------

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpHeaders contentTypeJson() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders h = contentTypeJson();
        h.setBearerAuth(token);
        return h;
    }

    private HttpEntity<String> authEntity(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private ResponseEntity<String> postJson(String path, Map<String, ?> body, String token) throws Exception {
        return http.postForEntity(url(path),
                new HttpEntity<>(json.writeValueAsString(body), authJson(token)),
                String.class);
    }

    private ResponseEntity<String> putJson(String path, Map<String, ?> body, String token) throws Exception {
        return http.exchange(url(path), HttpMethod.PUT,
                new HttpEntity<>(json.writeValueAsString(body), authJson(token)),
                String.class);
    }
}
