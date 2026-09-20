package app.springbootcrm.interfaces;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public-проекция {@link InterfaceLayout}. {@code layout} отдаётся как дерево
 * {@link LayoutNode} — фронт-конструктор работает с ним напрямую, без сырой JSON-строки.
 */
public record InterfaceLayoutDto(
        UUID id,
        String code,
        String name,
        List<LayoutNode> layout,
        boolean enabled,
        Instant createdAt,
        String createdBy,
        String updatedBy
) {
    public static InterfaceLayoutDto of(InterfaceLayout i) {
        return new InterfaceLayoutDto(
                i.getId(),
                i.getCode(),
                i.getName(),
                i.getLayout(),
                i.isEnabled(),
                i.getCreatedAt(),
                i.getCreatedBy() == null ? null : i.getCreatedBy().targetIdRaw(),
                i.getUpdatedBy() == null ? null : i.getUpdatedBy().targetIdRaw()
        );
    }
}
