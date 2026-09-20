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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Доказательная сюита: row-level фильтрация в модуле Calendar действительно скрывает
 * чужие события.
 *
 * <p>Сценарии:
 * <ul>
 *   <li>Carol создаёт событие → видит его в GET /api/activities;</li>
 *   <li>Dave (другой пользователь) НЕ видит событие Carol — его просто нет в list;</li>
 *   <li>Dave не может GET /api/activities/{carolEventId} → 404 (фильтр превращает невидимый
 *       row в «не существует», что корректно и для security: не leak'аем id);</li>
 *   <li>Admin видит ОБА события (Carol + Dave) — bypass через ROOT_READ;</li>
 *   <li>Dave не может DELETE/UPDATE событие Carol → 404 (фильтр) или 403;</li>
 *   <li>Carol успешно обновляет своё событие.</li>
 * </ul>
 *
 * <p>End-to-end иллюстрация того, что пользователю видны только записи, относящиеся
 * именно к нему.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActivityFilteringIT {

    @Autowired private TestRestTemplate http;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();

    private String adminToken;
    private String carolToken;
    private UUID carolId;
    private String daveToken;
    private UUID daveId;

    private UUID carolEventId;
    private UUID daveEventId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // Register Carol and Dave (they are not in the seed data)
        var carolJson = register("carol", "secret123");
        carolToken = carolJson.get("token").asText();
        carolId = UUID.fromString(carolJson.get("user").get("id").asText());

        var daveJson = register("dave", "secret123");
        daveToken = daveJson.get("token").asText();
        daveId = UUID.fromString(daveJson.get("user").get("id").asText());

        // Carol creates her own activity
        Instant start = Instant.parse("2026-06-01T10:00:00Z");
        Instant end   = Instant.parse("2026-06-01T11:00:00Z");
        ResponseEntity<String> rc = postJson("/api/activities",
                Map.of("title", "Carol's meeting", "description", "Visible to Carol only",
                       "startsAt", start.toString(), "endsAt", end.toString()),
                carolToken);
        assertThat(rc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        carolEventId = UUID.fromString(json.readTree(rc.getBody()).get("id").asText());

        // Dave creates his own activity
        ResponseEntity<String> rd = postJson("/api/activities",
                Map.of("title", "Dave's meeting", "description", "Visible to Dave only",
                       "startsAt", "2026-07-01T10:00:00Z",
                       "endsAt",   "2026-07-01T11:00:00Z"),
                daveToken);
        assertThat(rd.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        daveEventId = UUID.fromString(json.readTree(rd.getBody()).get("id").asText());
    }

    @Test
    @DisplayName("Carol sees only her own activity in /api/activities")
    void carol_sees_only_her_own_events() throws Exception {
        JsonNode arr = getJson("/api/activities", carolToken);
        assertThat(arr.isArray()).isTrue();
        // Every activity in the list must belong to Carol
        for (JsonNode e : arr) {
            UUID owner = UUID.fromString(e.get("ownerId").asText());
            assertThat(owner).as("Carol must see only her own activities").isEqualTo(carolId);
        }
        // ...and hers is actually present
        boolean foundCarolEvent = false;
        for (JsonNode e : arr) {
            if (carolEventId.equals(UUID.fromString(e.get("id").asText()))) {
                foundCarolEvent = true;
                break;
            }
        }
        assertThat(foundCarolEvent).isTrue();
    }

    @Test
    @DisplayName("Dave cannot see Carol's activity, even by direct GET on the id (404)")
    void dave_cant_see_carol_event_by_id() {
        ResponseEntity<String> r = http.exchange(
                url("/api/activities/" + carolEventId), HttpMethod.GET,
                authEntity(daveToken), String.class);
        // The Hibernate filter hides the row, so repo.findById returns Optional.empty()
        // and the service throws NoSuchElementException -> 404
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Admin sees BOTH activities (Carol + Dave)")
    void admin_sees_all_events() throws Exception {
        JsonNode arr = getJson("/api/activities", adminToken);
        boolean foundCarol = false, foundDave = false;
        for (JsonNode e : arr) {
            UUID id = UUID.fromString(e.get("id").asText());
            if (id.equals(carolEventId)) foundCarol = true;
            if (id.equals(daveEventId)) foundDave = true;
        }
        assertThat(foundCarol).as("Admin sees Carol's activity").isTrue();
        assertThat(foundDave).as("Admin sees Dave's activity").isTrue();
    }

    @Test
    @DisplayName("Admin can GET Carol's activity directly by id")
    void admin_can_get_carol_event() throws Exception {
        JsonNode node = getJson("/api/activities/" + carolEventId, adminToken);
        assertThat(node.get("ownerId").asText()).isEqualTo(carolId.toString());
    }

    @Test
    @DisplayName("Dave cannot delete Carol's activity -> 404")
    void dave_cant_delete_carol_event() {
        ResponseEntity<String> r = http.exchange(
                url("/api/activities/" + carolEventId), HttpMethod.DELETE,
                authEntity(daveToken), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Carol can update her own activity")
    void carol_can_update_her_event() throws Exception {
        ResponseEntity<String> r = putJson("/api/activities/" + carolEventId,
                Map.of("title", "Carol's meeting (updated)",
                       "description", "Updated",
                       "startsAt", "2026-06-01T10:00:00Z",
                       "endsAt",   "2026-06-01T12:00:00Z"),
                carolToken);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(json.readTree(r.getBody()).get("title").asText())
                .contains("updated");
    }

    @Test
    @DisplayName("Invalid activity (end < start) -> 400")
    void invalid_dates_return_400() throws Exception {
        ResponseEntity<String> r = postJson("/api/activities",
                Map.of("title", "Invalid range",
                       "startsAt", "2026-06-01T12:00:00Z",
                       "endsAt",   "2026-06-01T10:00:00Z"),
                carolToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ============ Helpers ============

    private String login(String username, String password) throws Exception {
        ResponseEntity<String> r = http.exchange(url("/api/auth/login"), HttpMethod.POST,
                jsonEntity(Map.of("username", username, "password", password), null), String.class);
        return json.readTree(r.getBody()).get("token").asText();
    }

    private JsonNode register(String username, String password) throws Exception {
        ResponseEntity<String> r = http.exchange(url("/api/auth/register"), HttpMethod.POST,
                jsonEntity(Map.of("username", username,
                        "email", username + "@e.com",
                        "displayName", username,
                        "password", password), null),
                String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        return json.readTree(r.getBody());
    }

    private JsonNode getJson(String path, String token) throws Exception {
        ResponseEntity<String> r = http.exchange(url(path), HttpMethod.GET,
                authEntity(token), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful())
                .as("GET %s: status=%s body=%s", path, r.getStatusCode(), r.getBody())
                .isTrue();
        return json.readTree(r.getBody());
    }

    private ResponseEntity<String> postJson(String path, Object body, String token) throws Exception {
        return http.exchange(url(path), HttpMethod.POST, jsonEntity(body, token), String.class);
    }

    private ResponseEntity<String> putJson(String path, Object body, String token) throws Exception {
        return http.exchange(url(path), HttpMethod.PUT, jsonEntity(body, token), String.class);
    }

    private HttpEntity<String> jsonEntity(Object body, String token) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(json.writeValueAsString(body), h);
    }

    private HttpEntity<Void> authEntity(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
