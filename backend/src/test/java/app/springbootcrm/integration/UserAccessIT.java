package app.springbootcrm.integration;

import app.springbootcrm.access.AccessRole;

import app.springbootcrm.bootstrap.AdminBootstrap;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end интеграционный тест ограничений доступа.
 *
 * <p>Проверяет:
 * <ul>
 *   <li>Анонимный запрос на защищённый endpoint → 401;</li>
 *   <li>Обычный пользователь видит BASIC-проекцию других, FULL — на самого себя;</li>
 *   <li>Admin видит FULL на любого;</li>
 *   <li>Обычный пользователь не может создавать/удалять/менять чужие записи;</li>
 *   <li>Обычный пользователь может менять собственный профиль через {@code PATCH /me};</li>
 *   <li>После назначения admin'ом роли — права пользователя меняются.</li>
 * </ul>
 *
 * <p>Использует test-profile (in-memory H2). Bootstrap admin'а запускается автоматически.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserAccessIT {

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    private final ObjectMapper json = new ObjectMapper();

    /** Токен admin'а (логинимся один раз в начале и переиспользуем). */
    private String adminToken;

    /** Токен alice (обычная зарегистрированная пользовательница). */
    private String aliceToken;
    private UUID aliceId;

    /** Bob — второй обычный пользователь, для проверки cross-visibility. */
    private String bobToken;
    private UUID bobId;

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");
        assertThat(adminToken).isNotBlank();

        var aliceJson = registerAndLogin("alice", "alice@example.com", "secret123");
        aliceToken = aliceJson.get("token").asText();
        aliceId = UUID.fromString(aliceJson.get("user").get("id").asText());

        var bobJson = registerAndLogin("bob", "bob@example.com", "secret123");
        bobToken = bobJson.get("token").asText();
        bobId = UUID.fromString(bobJson.get("user").get("id").asText());
    }

    // ============ Анонимный запрос ============

    @Test
    @DisplayName("Anonymous GET /api/users -> 401")
    void anonymous_users_returns_401() {
        ResponseEntity<String> r = http.exchange(
                url("/api/users"), HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ============ FULL vs BASIC проекция ============

    @Test
    @DisplayName("Alice -> GET /api/users/{bobId} -> BASIC (no email / accessGlobalFlags)")
    void alice_sees_bob_as_basic() throws Exception {
        JsonNode node = getJson("/api/users/" + bobId, aliceToken);
        assertThat(node.get("level").asText()).isEqualTo("basic");
        assertThat(node.get("email").isNull()).isTrue();
        assertThat(node.get("accessGlobalFlags").isNull()).isTrue();
        // username и displayName видны
        assertThat(node.get("username").asText()).isEqualTo("bob");
    }

    @Test
    @DisplayName("Alice -> GET /api/users/{aliceId} -> FULL (email is visible)")
    void alice_sees_self_as_full() throws Exception {
        JsonNode node = getJson("/api/users/" + aliceId, aliceToken);
        assertThat(node.get("level").asText()).isEqualTo("full");
        assertThat(node.get("email").asText()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("Alice -> GET /api/users/me -> FULL")
    void alice_me_is_full() throws Exception {
        JsonNode node = getJson("/api/users/me", aliceToken);
        assertThat(node.get("level").asText()).isEqualTo("full");
        assertThat(node.get("username").asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("Admin -> GET /api/users/{aliceId} -> FULL (an admin sees others in full)")
    void admin_sees_alice_as_full() throws Exception {
        JsonNode node = getJson("/api/users/" + aliceId, adminToken);
        assertThat(node.get("level").asText()).isEqualTo("full");
        assertThat(node.get("email").asText()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("passwordHash never reaches the DTO")
    void password_hash_never_leaks() throws Exception {
        for (String token : new String[]{adminToken, aliceToken}) {
            JsonNode node = getJson("/api/users/" + aliceId, token);
            assertThat(node.has("passwordHash")).isFalse();
            assertThat(node.has("password")).isFalse();
        }
    }

    // ============ Запись: admin vs обычный ============

    @Test
    @DisplayName("Alice cannot create a user -> 403")
    void alice_cannot_create_user() throws Exception {
        ResponseEntity<String> r = postJsonWith(
                "/api/users",
                Map.of("username", "mallory", "email", "m@e.com",
                        "displayName", "Mallory", "password", "secret123", "enabled", true),
                aliceToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Alice cannot delete bob -> 403")
    void alice_cannot_delete_bob() {
        ResponseEntity<String> r = http.exchange(
                url("/api/users/" + bobId),
                HttpMethod.DELETE,
                authEntity(aliceToken),
                String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Alice cannot PUT bob -> 403")
    void alice_cannot_admin_update_bob() throws Exception {
        ResponseEntity<String> r = putJsonWith(
                "/api/users/" + bobId,
                Map.of("displayName", "Pwned"),
                aliceToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Alice can PATCH /api/users/me (her own profile)")
    void alice_can_patch_self() throws Exception {
        ResponseEntity<String> r = patchJsonWith(
                "/api/users/me",
                Map.of("displayName", "Alice Updated"),
                aliceToken);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode node = json.readTree(r.getBody());
        assertThat(node.get("displayName").asText()).isEqualTo("Alice Updated");
    }

    @Test
    @DisplayName("Admin can create and delete a user")
    void admin_full_user_lifecycle() throws Exception {
        // Create
        ResponseEntity<String> rc = postJsonWith(
                "/api/users",
                Map.of("username", "tempuser", "email", "t@e.com",
                        "displayName", "Temp", "password", "secret123", "enabled", true),
                adminToken);
        assertThat(rc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tid = UUID.fromString(json.readTree(rc.getBody()).get("id").asText());

        // Delete
        ResponseEntity<String> rd = http.exchange(
                url("/api/users/" + tid), HttpMethod.DELETE,
                authEntity(adminToken), String.class);
        assertThat(rd.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ============ AccessRole: назначение / снятие ============

    @Test
    @DisplayName("Alice cannot create a role -> 403; an admin can")
    void access_roles_are_admin_only() throws Exception {
        // Alice → 403
        ResponseEntity<String> ra = postJsonWith(
                "/api/access-roles",
                Map.of("code", "USER_TEST", "name", "Test", "enabled", true),
                aliceToken);
        assertThat(ra.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Admin → 201
        ResponseEntity<String> radm = postJsonWith(
                "/api/access-roles",
                Map.of("code", "USER_TEST_2", "name", "Test 2", "enabled", true),
                adminToken);
        assertThat(radm.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Admin assigns a role to alice, then removes it; idempotent (one role per user)")
    void admin_assigns_and_unassigns_role() throws Exception {
        // Создаём роль (Admin)
        ResponseEntity<String> rc = postJsonWith(
                "/api/access-roles",
                Map.of("code", "AUDITOR",
                        "name", "Auditor",
                        "description", "Can read everything",
                        "accessTemplate", Map.of("globalFlags", 4 /* ADMIN_READ */),
                        "enabled", true),
                adminToken);
        assertThat(rc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID roleId = UUID.fromString(json.readTree(rc.getBody()).get("id").asText());

        // Назначаем — PUT /api/users/{id} с roleId + roleProvided (одна роль).
        ResponseEntity<String> ra = putJsonWith(
                "/api/users/" + aliceId,
                Map.of("roleId", roleId.toString(), "roleProvided", true),
                adminToken);
        assertThat(ra.getStatusCode().is2xxSuccessful())
                .as("assign role: %s", ra.getBody()).isTrue();

        // Her profile must now carry exactly this role (the single roleId field)
        JsonNode aliceFull = getJson("/api/users/" + aliceId, adminToken);
        assertThat(aliceFull.get("roleId").isNull()).isFalse();
        assertThat(aliceFull.get("roleId").asText())
                .as("Alice must have roleId %s", roleId)
                .isEqualTo(roleId.toString());

        // accessGlobalFlags must contain ADMIN_READ (=4) or the result of expanding it
        int g = aliceFull.get("accessGlobalFlags").asInt();
        assertThat(g & 4 /*ADMIN_READ*/).as("Alice must have ADMIN_READ").isEqualTo(4);

        // Clear it: PUT with roleId=null and roleProvided=true.
        Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("roleId", null);
        clear.put("roleProvided", true);
        ResponseEntity<String> ru = putJsonWith("/api/users/" + aliceId, clear, adminToken);
        assertThat(ru.getStatusCode().is2xxSuccessful())
                .as("clear role: %s", ru.getBody()).isTrue();

        JsonNode aliceCleared = getJson("/api/users/" + aliceId, adminToken);
        assertThat(aliceCleared.get("roleId").isNull())
                .as("after the role is cleared roleId == null").isTrue();
    }

    // ============ Helpers ============

    private String login(String username, String password) throws Exception {
        ResponseEntity<String> r = postJson("/api/auth/login",
                Map.of("username", username, "password", password));
        assertThat(r.getStatusCode().is2xxSuccessful())
                .as("Login failed: %s", r.getBody()).isTrue();
        return json.readTree(r.getBody()).get("token").asText();
    }

    private JsonNode registerAndLogin(String username, String email, String password)
            throws Exception {
        ResponseEntity<String> r = postJson("/api/auth/register",
                Map.of("username", username, "email", email,
                        "displayName", username, "password", password));
        assertThat(r.getStatusCode().is2xxSuccessful())
                .as("Register failed: %s", r.getBody()).isTrue();
        return json.readTree(r.getBody());
    }

    private JsonNode getJson(String path, String token) throws Exception {
        ResponseEntity<String> r = http.exchange(
                url(path), HttpMethod.GET, authEntity(token), String.class);
        assertThat(r.getStatusCode().is2xxSuccessful())
                .as("GET %s failed: status=%s body=%s", path, r.getStatusCode(), r.getBody())
                .isTrue();
        return json.readTree(r.getBody());
    }

    private ResponseEntity<String> postJson(String path, Object body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(json.writeValueAsString(body), h), String.class);
    }

    private ResponseEntity<String> postJsonWith(String path, Object body, String token)
            throws Exception {
        return doWithBody(path, HttpMethod.POST, body, token);
    }

    private ResponseEntity<String> putJsonWith(String path, Object body, String token)
            throws Exception {
        return doWithBody(path, HttpMethod.PUT, body, token);
    }

    private ResponseEntity<String> patchJsonWith(String path, Object body, String token)
            throws Exception {
        return doWithBody(path, HttpMethod.PATCH, body, token);
    }

    private ResponseEntity<String> doWithBody(String path, HttpMethod m, Object body, String token)
            throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return http.exchange(url(path), m,
                new HttpEntity<>(json.writeValueAsString(body), h), String.class);
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
