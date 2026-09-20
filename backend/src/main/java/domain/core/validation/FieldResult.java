package domain.core.validation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Результат валидации одного реквизита — структура, связывающая <b>конкретный
 * реквизит</b> ({@code field}) и <b>результат</b> его проверки ({@code valid} +
 * {@code message}), плюс доп-информацию ({@code meta}: min/max/maxLength/…), которую
 * бэкенд может дать фронтенду для показа в оверлей-панели над полем.
 *
 * @param field   имя реквизита (совпадает с ключом в форме/DTO)
 * @param valid   прошла ли проверка
 * @param message текст первой ошибки (null, если valid)
 * @param meta    параметры ограничений поля (для подсказок UI)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldResult(
        String field,
        boolean valid,
        String message,
        Map<String, Object> meta
) {
    public static FieldResult ok(String field, Map<String, Object> meta) {
        return new FieldResult(field, true, null, meta);
    }

    public static FieldResult fail(String field, String message, Map<String, Object> meta) {
        return new FieldResult(field, false, message, meta);
    }

    /** Ответ на полную валидацию объекта: общий флаг + результаты по полям. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ObjectResult(boolean valid, List<FieldResult> results) {}
}
