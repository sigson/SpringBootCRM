package app.modules.sqlworkbench.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Связывает SecurityContext c {@link WorkbenchAccessPolicy}: собирает {@link AccessContext}
 * из текущей аутентификации и делегирует проверку в политику host-продукта.
 *
 * Используется как контроллерами-гейтвея, так и проксирующим контроллером.
 */
@Component("sqlworkbenchAccessGuard")
public class AccessGuard {

    private final WorkbenchAccessPolicy policy;

    public AccessGuard(WorkbenchAccessPolicy policy) { this.policy = policy; }

    public AccessContext currentContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getName())) {
            return AccessContext.of("anonymous", List.of());
        }
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toList());
        return AccessContext.of(auth.getName(), authorities);
    }

    public void check(WorkbenchAction action) {
        policy.check(action, currentContext());
    }

    public void check(WorkbenchAction action, String dsId, String schema, String table) {
        policy.check(action, currentContext().withTarget(dsId, schema, table));
    }

    public boolean allowed(WorkbenchAction action, String dsId, String schema, String table) {
        return policy.isAllowed(action, currentContext().withTarget(dsId, schema, table));
    }
}
