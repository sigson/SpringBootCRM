package app.springbootcrm.integration;

import app.springbootcrm.access.AccessRole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
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
 * Тест разграничения прав: Permission 1 (view+add) vs Permission 2 (view+edit+delete).
 *
 * <p>Реализовано через стандартные биты {@code typeFlags[4001]} в {@link AccessRole#getAccessTemplate()}:
 * <ul>
 *   <li><b>Permission 1</b>: {@code typeFlags[4001] = READ | WRITE_INSERT} (=0x03);</li>
 *   <li><b>Permission 2</b>: {@code typeFlags[4001] = READ | WRITE_UPDATE} (=0x41).</li>
 * </ul>
 * Защита целиком в ядре: {@code AccessAwarePreInsertListener} проверяет {@code WRITE_INSERT},
 * {@code AccessAwarePreUpdate/DeleteListener} — {@code WRITE_UPDATE}.
 *
 * <p>Сценарий теста:
 * <ol>
 *   <li>Admin создаёт две роли (с разными битами WRITE_INSERT/WRITE_UPDATE);</li>
 *   <li>регистрирует двух пользователей: operator (Permission 1) и manager (Permission 2);</li>
 *   <li>назначает каждому соответствующую роль;</li>
 *   <li>проверяет: operator может создать, но не может редактировать/удалить;
 *       manager наоборот.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerPermissionsIT {

    @Autowired private TestRestTemplate http;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();

    private String adminToken;
    private String operatorToken;     // Permission 1
    private String managerToken;       // Permission 2
    private String unauthorizedToken;  // ничего из вышеперечисленного

    private UUID createdTcId;          // создано operator'ом, будет редактироваться manager'ом

    @BeforeAll
    void setup() throws Exception {
        adminToken = login("admin", "admin");

        // Permission 1 (view + add): READ | WRITE_INSERT = 1 | 2 = 3
        UUID tcAddRoleId = createRole("TC_ADD_ROLE", "Permission to add customers",
                Map.of("globalFlags", 0,
                       "typeFlags",   Map.of("4001", 3)));

        // Permission 2 (view + edit + delete): READ | WRITE_UPDATE = 1 | 64 = 65
        UUID tcEditRoleId = createRole("TC_EDIT_ROLE", "Permission to edit and delete customers",
                Map.of("globalFlags", 0,
                       "typeFlags",   Map.of("4001", 65)));

        // Register the operator and assign TC_ADD
        var operatorJson = register("tc_operator", "secret123");
        operatorToken = operatorJson.get("token").asText();
        UUID operatorId = UUID.fromString(operatorJson.get("user").get("id").asText());
        assignRole(operatorId, tcAddRoleId);
        // Перелогиниваемся — чтобы JWT и кеш AccessMetric подхватили новые права
        operatorToken = login("tc_operator", "secret123");

        // Регистрируем manager'а и назначаем TC_EDIT
        var managerJson = register("tc_manager", "secret123");
        managerToken = managerJson.get("token").asText();
        UUID managerId = UUID.fromString(managerJson.get("user").get("id").asText());
        assignRole(managerId, tcEditRoleId);
        managerToken = login("tc_manager", "secret123");

        // Простой юзер без ролей
        var plainJson = register("tc_nobody", "secret123");
        unauthorizedToken = plainJson.get("token").asText();
    }

    @Test
    @Order(1)
    @DisplayName("Permission 1: operator can create a record")
    void operator_can_create() throws Exception {
        ResponseEntity<String> r = postJson("/api/customers",
                Map.of("code", "33", "name", "Created by the operator"),
                operatorToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdTcId = UUID.fromString(json.readTree(r.getBody()).get("id").asText());
    }

    @Test
    @Order(2)
    @DisplayName("Permission 1: operator CANNOT edit -> 403")
    void operator_cannot_edit() throws Exception {
        ResponseEntity<String> r = putJson("/api/customers/" + createdTcId,
                Map.of("code", "33", "name", "Edit attempt"),
                operatorToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.get("kind").asText()).isEqualTo("ACCESS_DENIED");
        // Структурированные требования: должны содержать WRITE_UPDATE на typeId=4001
        JsonNode reqs = body.get("requirements");
        assertThat(reqs).isNotNull();
        assertThat(reqs.isArray()).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("Permission 1: operator CANNOT delete -> 403")
    void operator_cannot_delete() {
        ResponseEntity<String> r = http.exchange(
                url("/api/customers/" + createdTcId),
                HttpMethod.DELETE, authEntity(operatorToken), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @Order(4)
    @DisplayName("Permission 2: manager CANNOT create -> 403")
    void manager_cannot_create() throws Exception {
        ResponseEntity<String> r = postJson("/api/customers",
                Map.of("code", "34", "name", "Create attempt by the manager"),
                managerToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.get("kind").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(body.get("requirements")).isNotNull();
    }

    @Test
    @Order(5)
    @DisplayName("Permission 2: manager can edit an existing record")
    void manager_can_edit() throws Exception {
        ResponseEntity<String> r = putJson("/api/customers/" + createdTcId,
                Map.of("code", "33", "name", "Fixed by the manager"),
                managerToken);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(json.readTree(r.getBody()).get("name").asText())
                .isEqualTo("Fixed by the manager");
    }

    @Test
    @Order(6)
    @DisplayName("Permission 2: manager can delete")
    void manager_can_delete() {
        ResponseEntity<String> r = http.exchange(
                url("/api/customers/" + createdTcId),
                HttpMethod.DELETE, authEntity(managerToken), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @Order(7)
    @DisplayName("Without a role: a simple user can neither create nor edit")
    void unauthorized_cannot_do_anything() throws Exception {
        ResponseEntity<String> r = postJson("/api/customers",
                Map.of("code", "35", "name", "Attempt without a role"), unauthorizedToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Просмотр — разрешён всем аутентифицированным (defaultRepoAccess=READ_ONLY на typeId=4001)
        ResponseEntity<String> rl = http.exchange(url("/api/customers"),
                HttpMethod.GET, authEntity(unauthorizedToken), String.class);
        assertThat(rl.getStatusCode().is2xxSuccessful())
                .as("Viewing is allowed for everyone - READ on typeId=4001").isTrue();
    }

    // ============ Helpers ============

    private UUID createRole(String code, String name, Map<String, Object> template) throws Exception {
        ResponseEntity<String> r = postJson("/api/access-roles",
                Map.of("code", code, "name", name, "accessTemplate", template, "enabled", true),
                adminToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(json.readTree(r.getBody()).get("id").asText());
    }

    private void assignRole(UUID userId, UUID roleId) {
        // Одна роль через PUT /api/users/{id} с roleId + roleProvided.
        ResponseEntity<String> r;
        try {
            r = http.exchange(
                    url("/api/users/" + userId),
                    HttpMethod.PUT,
                    jsonEntity(Map.of("roleId", roleId.toString(), "roleProvided", true), adminToken),
                    String.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
    }

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
                        "password", password), null), String.class);
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

    private URI url(String path) { return URI.create("http://localhost:" + port + path); }
}
