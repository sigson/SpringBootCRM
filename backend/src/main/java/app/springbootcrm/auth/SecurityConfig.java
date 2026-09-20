package app.springbootcrm.auth;

import domain.core.web.AccessContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import domain.core.web.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Security-цепочка SpringBootCRM'а:
 * <ul>
 *   <li>сессии — STATELESS (JWT);</li>
 *   <li>CSRF — выключен (cookie с {@code SameSite=Lax} + JSON-API);</li>
 *   <li>{@code /api/auth/login}, {@code /api/auth/register} — публичные;</li>
 *   <li>{@code /api/**} — требуют аутентификации;</li>
 *   <li>{@code /h2/**}, {@code /actuator/health}, {@code /error} — публичные.</li>
 * </ul>
 *
 * <p><b>Важная деталь Spring Security 6:</b> {@code SecurityContextHolderFilter}
 * (внутренний) очищает {@code SecurityContextHolder} и подгружает «deferred context»
 * из репозитория — в stateless-режиме это пустой контекст. Поэтому JWT-фильтр,
 * запущенный как обычный servlet-filter <i>перед</i> SS-цепочкой, бесполезен:
 * SS сразу затрёт его {@code Authentication}.
 *
 * <p>Решение: обоих наших фильтров ({@link JwtAuthenticationFilter} и
 * {@link AccessContextFilter}) принудительно вставляем <b>внутрь</b> SS-цепочки
 * через {@code http.addFilterBefore} / {@code addFilterAfter}. Авто-регистрацию
 * как servlet-фильтров отключаем через {@link FilterRegistrationBean#setEnabled(boolean)}.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** Отдельный «чистый» mapper: конверт ошибки не проходит через access-aware сериализацию. */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    @Value("${springbootcrm.cors.allowed-origins:*}")
    private String allowedOriginsRaw;

    /**
     * Главная цепочка фильтров. Внутрь неё встраиваем JWT-аутентификацию
     * и AccessContext-биндинг — иначе SecurityContextHolderFilter затрёт
     * наш {@code Authentication}.
     */
    @Bean
    public SecurityFilterChain crmSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            AccessContextFilter accessCtxFilter) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers("/h2/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                // Без явного entry point Spring Security отвечает 403 и на
                // неаутентифицированный запрос, из-за чего клиент не может отличить
                // «протухшая сессия» от «не хватает прав» и не уходит на логин.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler(this::writeForbidden))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                // 1) JWT-фильтр — перед UsernamePasswordAuthenticationFilter
                //    (внутрь SS-цепочки, ПОСЛЕ SecurityContextHolderFilter)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // 2) AccessContextFilter — сразу за JWT-фильтром, чтобы прочитать
                //    Authentication, которое уже выставлено, и забиндить ThreadLocal
                //    AccessContextHolder для @AccessFiltered и AOP-проверок
                .addFilterAfter(accessCtxFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Запрещаем Spring Boot'у автоматически регистрировать {@link JwtAuthenticationFilter}
     * как самостоятельный servlet-filter — иначе он сработает дважды (один раз снаружи
     * SS-цепочки, второй раз внутри). А срабатывание снаружи бесполезно из-за
     * {@code SecurityContextHolderFilter} (см. javadoc класса).
     */
    /** 401 + стандартный ErrorEnvelope для анонимного запроса к защищённому ресурсу. */
    private void writeUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   AuthenticationException ex) throws IOException {
        writeEnvelope(response, ErrorEnvelope.unauthorized(
                "Authentication required", request.getRequestURI()));
    }

    /** 403 для аутентифицированного пользователя, которому не хватает прав. */
    private void writeForbidden(HttpServletRequest request,
                                HttpServletResponse response,
                                AccessDeniedException ex) throws IOException {
        writeEnvelope(response, ErrorEnvelope.accessDenied(
                "Access denied", request.getRequestURI(), null));
    }

    private void writeEnvelope(HttpServletResponse response, ErrorEnvelope body)
            throws IOException {
        response.setStatus(body.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ERROR_MAPPER.writeValue(response.getOutputStream(), body);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> disableJwtFilterAutoRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    /**
     * То же для {@link AccessContextFilter}: он зарегистрирован в core как {@code @Component
     * @Order(HIGHEST+50)} — нам нужно отключить эту авто-регистрацию и поставить его в нужное
     * место внутри SS-цепочки.
     */
    @Bean
    public FilterRegistrationBean<AccessContextFilter> disableAccessContextFilterAutoRegistration(
            AccessContextFilter filter) {
        FilterRegistrationBean<AccessContextFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        // Максимально відкритий CORS: дозволяємо БУДЬ-ЯКИЙ origin (хост/порт/схема).
        // При allowCredentials(true) не можна віддавати буквальну "*" в
        // Access-Control-Allow-Origin, тому використовуємо ORIGIN PATTERNS:
        // "*" як патерн дозволено разом із credentials — Spring підставить у
        // відповідь конкретний Origin запиту. Це дозволяє фронтенду крутитися
        // на будь-якому хості (localhost, 127.0.0.1, LAN-IP типу 10.80.227.18)
        // і будь-якому порту без правок CORS.
        if (origins.isEmpty()) {
            origins = List.of("*");
        }
        cfg.setAllowedOriginPatterns(origins);
        cfg.setAllowedMethods(List.of("*"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}