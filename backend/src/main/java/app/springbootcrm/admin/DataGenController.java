package app.springbootcrm.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <h2>REST-контролер генерації тестових даних.</h2>
 *
 * <p>Admin-only (перевірка у {@link DataGenService}). Усі ендпоінти під
 * {@code /api/admin/datagen}:
 * <ul>
 *   <li>{@code GET  /flag}      — flag-маркер (UI-підказка);</li>
 *   <li>{@code GET  /targets}   — перелік типів, доступних для генерації;</li>
 *   <li>{@code POST /generate}  — згенерувати N записів довільного типу
 *       (для табличних частин N — на кожного власника);</li>
 *   <li>{@code POST /users}     — згенерувати N користувачів (роль/пароль/інтерфейс);</li>
 *   <li>{@code POST /purge}     — зачистити всі згенеровані дані.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/datagen")
public class DataGenController {

    private final DataGenService service;

    public DataGenController(DataGenService service) {
        this.service = service;
    }

    @GetMapping("/flag")
    public Map<String, String> flag() {
        return Map.of("flag", DataGenService.GEN_FLAG);
    }

    /** Перелік типів (довідники/регістри/табличні частини), які можна генерувати. */
    @GetMapping("/targets")
    public List<DataGenService.GenTarget> targets() {
        return service.targets();
    }

    /** Універсальна генерація одного типу. */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody GenerateRequest req) {
        boolean benchmark = req.benchmark() != null && req.benchmark();
        boolean noLogging = req.noLogging() != null && req.noLogging();
        int created = service.generate(req.typeId(), req.count(),
                new DataGenService.GenOptions(benchmark, noLogging));
        return Map.of("created", created, "typeId", req.typeId(),
                "benchmark", benchmark, "noLogging", noLogging);
    }

    /**
     * «Очистити весь тип»: видаляє ВСІ рядки типу (і реальні, і згенеровані).
     * Потрібно для типів, наповнених у режимі «без логування». Викликається з
     * UI лише з явним підтвердженням.
     */
    @PostMapping("/purge-type")
    public Map<String, Object> purgeType(@RequestBody PurgeTypeRequest req) {
        int deleted = service.purgeType(req.typeId());
        return Map.of("deleted", deleted, "typeId", req.typeId());
    }

    @PostMapping("/users")
    public Map<String, Object> generateUsers(@RequestBody GenerateUsersRequest req) {
        int created = service.generateUsers(
                req.count(), req.defaultRoleId(), req.defaultPassword(), req.defaultInterfaceId());
        return Map.of("created", created, "kind", "users");
    }

    @PostMapping("/purge")
    public DataGenService.PurgeReport purge() {
        return service.purge();
    }

    // -------- Request-records --------

    public record GenerateRequest(
            @NotNull Long typeId,
            @NotNull @Min(1) Integer count,
            /** Benchmark-режим (швидке наповнення; ≤10 кешованих ссилок, скаляри на порцію). */
            Boolean benchmark,
            /** Без логування: не писати DataGenLog по рядку (½ вставок; точковий purge недоступний). */
            Boolean noLogging) {}

    public record PurgeTypeRequest(@NotNull Long typeId) {}

    public record GenerateUsersRequest(
            @NotNull @Min(1) Integer count,
            /** Роль за замовчуванням (nullable). */
            UUID defaultRoleId,
            /** Пароль за замовчуванням (nullable → стандартний). */
            String defaultPassword,
            /** Інтерфейс за замовчуванням (nullable). */
            UUID defaultInterfaceId) {}
}
