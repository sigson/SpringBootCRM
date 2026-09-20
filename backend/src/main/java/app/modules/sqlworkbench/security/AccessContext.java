package app.modules.sqlworkbench.security;

import java.util.Collection;
import java.util.Set;

/**
 * Контекст вызова, передаваемый в {@link WorkbenchAccessPolicy}.
 * Содержит идентификатор пользователя и его роли (из JWT/SecurityContext),
 * а также целевой датасорс и таблицу (если применимо).
 */
public record AccessContext(
        String principal,
        Collection<String> authorities,
        String dataSourceId,
        String schema,
        String table) {

    public static AccessContext of(String principal, Collection<String> authorities) {
        return new AccessContext(principal, authorities == null ? Set.of() : authorities, null, null, null);
    }

    public AccessContext withTarget(String dsId, String schema, String table) {
        return new AccessContext(principal, authorities, dsId, schema, table);
    }

    public boolean hasAuthority(String a) {
        return authorities != null && authorities.contains(a);
    }
}
