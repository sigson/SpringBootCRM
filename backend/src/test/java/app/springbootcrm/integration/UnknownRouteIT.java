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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Поведение на несуществующем маршруте.
 *
 * <p>Раньше такой запрос доходил до catch-all'а {@code ErrorEnvelopeAdvice} и отдавал
 * 500 со стеком в логе: Spring бросает {@code NoResourceFoundException}, а она —
 * обычное {@code Exception}. Клиент не мог отличить «сервер сломался» от «такого
 * эндпоинта нет», а разница существенная — по ней, в частности, фронтенд понимает,
 * что опциональный модуль выключен.
 *
 * <p>Профиль {@code test} держит опциональные модули выключенными, поэтому их база
 * пути — как раз настоящий несуществующий маршрут.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnknownRouteIT {

    @Autowired private TestRestTemplate http;
    @LocalServerPort private int port;
    private final ObjectMapper json = new ObjectMapper();

    private String token;

    @BeforeAll
    void login() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = http.postForEntity(url("/api/auth/login"),
                new HttpEntity<>(json.writeValueAsString(
                        Map.of("username", "admin", "password", "admin")), h),
                String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        token = json.readTree(r.getBody()).get("token").asText();
    }

    @Test
    @DisplayName("Несуществующий эндпоинт -> 404 с envelope NOT_FOUND, а не 500")
    void unknown_endpoint_returns_404() throws Exception {
        ResponseEntity<String> r = get("/api/definitely-no-such-endpoint");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.get("kind").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("path").asText()).isEqualTo("/api/definitely-no-such-endpoint");
    }

    @Test
    @DisplayName("База пути выключенного модуля -> 404, по нему фронтенд и прячет раздел")
    void disabled_module_base_path_returns_404() {
        // Профиль test выключает sqlworkbench; модуль компоновки требует его и тоже не поднят.
        assertThat(get("/api/sqlworkbench/capabilities").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/dcs/capabilities").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Существующий эндпоинт по-прежнему отвечает")
    void known_endpoint_still_works() {
        assertThat(get("/api/reports").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Неаутентифицированный запрос отсекается раньше маршрутизации — 401")
    void anonymous_unknown_endpoint_returns_401() {
        ResponseEntity<String> r = http.exchange(
                url("/api/definitely-no-such-endpoint"),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> get(String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private URI url(String path) { return URI.create("http://localhost:" + port + path); }
}
