package app.springbootcrm.documents.activity;

import app.springbootcrm.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO события календаря.
 *
 * <p>Пару {@code ownerId} + {@code ownerTypeId} клиент может передать в universal reference
 * resolver ({@code POST /api/references/resolve}), чтобы получить читаемое отображение
 * (в том числе для администратора, который видит чужие события).
 */
public record ActivityDto(
        UUID id,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        UUID ownerId,
        long ownerTypeId,
        /** Связанный объект: typeId варианта (null, если не задан). */
        Long subjectTypeId,
        /** UUID связанного объекта (null, если не задан). */
        UUID subjectId
) {

    public static ActivityDto of(Activity e) {
        UUID ownerId = null;
        if (e.getOwner() != null && e.getOwner().targetIdRaw() != null) {
            try { ownerId = UUID.fromString(e.getOwner().targetIdRaw()); }
            catch (IllegalArgumentException ignored) {}
        }
        Long subjectTypeId = null;
        UUID subjectId = null;
        var subj = e.getSubject();
        if (subj != null && subj.targetIdRaw() != null) {
            subjectTypeId = subj.targetTypeId();
            try { subjectId = UUID.fromString(subj.targetIdRaw()); }
            catch (IllegalArgumentException ignored) { subjectTypeId = null; }
        }
        return new ActivityDto(
                e.getId(),
                e.getTitle(),
                e.getDescription(),
                e.getStartsAt(),
                e.getEndsAt(),
                ownerId,
                User.TYPE_ID,
                subjectTypeId,
                subjectId
        );
    }
}
