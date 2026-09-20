package app.springbootcrm.common;

import java.util.List;

/**
 * Универсальная обёртка страницы для chunked-загрузки списков.
 *
 * <p>Используется фронтендом для виртуального скролла / постраничного просмотра:
 * {@code total} позволяет рассчитать полную высоту области прокрутки (скролл
 * выглядит так, будто загружены все строки), хотя фактически грузятся только
 * нужные чанки.
 *
 * @param content строки текущей страницы
 * @param page    индекс страницы (0-based)
 * @param size    размер страницы (лимит)
 * @param total   общее число строк (с учётом row-level фильтров)
 */
public record PageResponse<T>(List<T> content, int page, int size, long total) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long total) {
        return new PageResponse<>(content, page, size, total);
    }
}
