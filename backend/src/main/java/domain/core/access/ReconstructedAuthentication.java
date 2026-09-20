package domain.core.access;

import domain.core.ddd.IdCodec;
import domain.core.ddd.UserAggregate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * Authentication, восстановленный из загруженного {@link UserAggregate} (например,
 * в Axon-handler'е). Granted-authorities выводятся из {@code globalFlags}
 * пользователя: ADMIN/ROOT-биты → соответствующие Spring-роли.
 *
 * <p>Per-type права лежат в {@code AccessMetric.typeFlags}; проверка идёт через
 * {@link AccessResolver}, а не через {@code @PreAuthorize}.
 */
public final class ReconstructedAuthentication implements Authentication {

    private final UserAggregate<?> user;
    private final List<GrantedAuthority> authorities;

    public ReconstructedAuthentication(UserAggregate<?> user) {
        this.user = user;
        this.authorities = authoritiesFromFlags(user.getAccess().globalFlags());
    }

    private static List<GrantedAuthority> authoritiesFromFlags(int globalFlags) {
        int x = AccessFlags.expand(globalFlags);
        java.util.ArrayList<GrantedAuthority> out = new java.util.ArrayList<>();
        out.add(new SimpleGrantedAuthority("ROLE_USER"));
        if ((x & AccessFlags.ADMIN_READ)  != 0) out.add(new SimpleGrantedAuthority("ROLE_ADMIN_READ"));
        if ((x & AccessFlags.ADMIN_WRITE) != 0) out.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if ((x & AccessFlags.ROOT_READ)   != 0) out.add(new SimpleGrantedAuthority("ROLE_ROOT_READ"));
        if ((x & AccessFlags.ROOT_WRITE)  != 0) out.add(new SimpleGrantedAuthority("ROLE_ROOT"));
        return List.copyOf(out);
    }

    @Override public String getName()        { return IdCodec.encode(user.getId()); }
    @Override public Object getCredentials() { return null; }
    @Override public Object getDetails()     { return user; }
    @Override public Object getPrincipal()   { return user; }
    @Override public boolean isAuthenticated() { return true; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override public void setAuthenticated(boolean authenticated) {}
}
