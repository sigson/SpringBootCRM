package domain.core.web;

import domain.core.access.AccessContext;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessKeyHasher;
import domain.core.access.AccessResolver;
import domain.core.access.PrincipalRefResolver;
import domain.core.access.ProjectionDirection;
import domain.core.access.SystemAccessContexts;
import domain.core.bootstrap.MetadataSnapshotProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Создаёт {@link AccessContext} из текущего {@link Authentication} и биндит на ThreadLocal.
 *
 * <p>Two-phase hash:
 * <ol>
 *   <li>{@link AccessKeyHasher#lightHash(Authentication)} — стабильный per-token, для lookup'а
 *       {@code AccessMetric};</li>
 *   <li>{@link AccessKeyHasher#fullHash(Authentication, AccessMetric)} — после загрузки metric'а,
 *       включает {@code metric.payload().hashCode()} → инвалидация cache'а после grant'а.</li>
 * </ol>
 *
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AccessContextFilter extends OncePerRequestFilter {

    private final AccessContextHolder holder;
    private final AccessKeyHasher hasher;
    private final PrincipalRefResolver principalResolver;
    private final AccessResolver accessResolver;
    private final MetadataSnapshotProvider snapshots;
    private final SystemAccessContexts systems;

    public AccessContextFilter(AccessContextHolder holder,
                                AccessKeyHasher hasher,
                                PrincipalRefResolver principalResolver,
                                AccessResolver accessResolver,
                                MetadataSnapshotProvider snapshots,
                                SystemAccessContexts systems) {
        this.holder = holder;
        this.hasher = hasher;
        this.principalResolver = principalResolver;
        this.accessResolver = accessResolver;
        this.snapshots = snapshots;
        this.systems = systems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AccessContext ctx;
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            ctx = null;     // AOP filter activator поставит system-read-only deny-all default
        } else {
            String light = hasher.lightHash(auth);
            var ref = principalResolver.resolve(auth);
            ctx = new AccessContext(auth, ref, accessResolver, snapshots.get(),
                    ProjectionDirection.OUTBOUND, light);
            // upgrade to full hash после первого metric'а
            var metric = accessResolver.userMetricFor(ctx);
            String full = hasher.fullHash(auth, metric);
            ctx = new AccessContext(auth, ref, accessResolver, snapshots.get(),
                    ProjectionDirection.OUTBOUND, full);
        }
        if (ctx == null) {
            chain.doFilter(request, response);
            return;
        }
        try (var ignored = holder.bind(ctx)) {
            chain.doFilter(request, response);
        }
    }
}
