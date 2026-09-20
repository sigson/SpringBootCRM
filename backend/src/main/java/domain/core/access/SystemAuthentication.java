package domain.core.access;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * Системный {@link Authentication} с реальными {@link GrantedAuthority} в зависимости от {@link Mode}.
 */
public final class SystemAuthentication implements Authentication {

    public enum Mode { READ_ONLY, READ_WRITE, MAX_PRIVILEGED, READ_ONLY_FOR_TYPE }

    private static final List<GrantedAuthority> READ_ONLY_AUTH = List.of(
            new SimpleGrantedAuthority("ROLE_SYSTEM"),
            new SimpleGrantedAuthority("ROLE_SYSTEM_READ"));

    private static final List<GrantedAuthority> READ_WRITE_AUTH = List.of(
            new SimpleGrantedAuthority("ROLE_SYSTEM"),
            new SimpleGrantedAuthority("ROLE_SYSTEM_READ"),
            new SimpleGrantedAuthority("ROLE_SYSTEM_WRITE"));

    private static final List<GrantedAuthority> MAX_PRIVILEGED_AUTH = List.of(
            new SimpleGrantedAuthority("ROLE_SYSTEM"),
            new SimpleGrantedAuthority("ROLE_SYSTEM_READ"),
            new SimpleGrantedAuthority("ROLE_SYSTEM_WRITE"),
            new SimpleGrantedAuthority("ROLE_MIGRATION"),
            new SimpleGrantedAuthority("GRANT_ADMIN_ROOT"));

    private static final List<GrantedAuthority> READ_ONLY_FOR_TYPE_AUTH = List.of(
            new SimpleGrantedAuthority("ROLE_SYSTEM_BOOTSTRAP"));

    private final Mode mode;
    private final long typeIdRestriction;

    private SystemAuthentication(Mode mode, long typeIdRestriction) {
        this.mode = mode;
        this.typeIdRestriction = typeIdRestriction;
    }

    public static SystemAuthentication readOnly()      { return new SystemAuthentication(Mode.READ_ONLY,      -1); }
    public static SystemAuthentication readWrite()     { return new SystemAuthentication(Mode.READ_WRITE,     -1); }
    public static SystemAuthentication maxPrivileged() { return new SystemAuthentication(Mode.MAX_PRIVILEGED, -1); }
    public static SystemAuthentication readOnlyFor(long typeId) {
        return new SystemAuthentication(Mode.READ_ONLY_FOR_TYPE, typeId);
    }

    public boolean isMaxPrivileged()           { return mode == Mode.MAX_PRIVILEGED; }
    public boolean isBootstrapFor(long typeId) {
        return mode == Mode.READ_ONLY_FOR_TYPE && typeIdRestriction == typeId;
    }

    public AccessMetric synthesizedMetric() {
        return switch (mode) {
            case READ_ONLY          -> AccessMetric.empty().withGlobalFlags(AccessFlags.READ);
            case READ_WRITE         -> AccessMetric.empty().withGlobalFlags(AccessFlags.READ | AccessFlags.WRITE);
            case MAX_PRIVILEGED     -> AccessMetric.empty().withGlobalFlags(AccessFlags.ROOT_WRITE);
            case READ_ONLY_FOR_TYPE -> AccessMetric.empty().withTypeFlags(typeIdRestriction, AccessFlags.ROOT_READ);
        };
    }

    @Override public String getName()         { return AccessContext.SYSTEM_PRINCIPAL_ID; }
    @Override public Object getCredentials()  { return null; }
    @Override public Object getDetails()      { return null; }
    @Override public Object getPrincipal()    { return AccessContext.SYSTEM_PRINCIPAL_ID; }
    @Override public boolean isAuthenticated(){ return true; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return switch (mode) {
            case READ_ONLY          -> READ_ONLY_AUTH;
            case READ_WRITE         -> READ_WRITE_AUTH;
            case MAX_PRIVILEGED     -> MAX_PRIVILEGED_AUTH;
            case READ_ONLY_FOR_TYPE -> READ_ONLY_FOR_TYPE_AUTH;
        };
    }

    @Override public void setAuthenticated(boolean authenticated) {}
}
