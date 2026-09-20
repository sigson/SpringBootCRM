package domain.core.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Конфигурация type-level grants. Маппится из {@code app.ddd.access.grants.*} в yaml.
 * Текущая реализация — пустая структура, расширяется по мере нужд приложения.
 */
@ConfigurationProperties(prefix = "app.ddd.access.grants")
public class GrantsProperties {

    private List<TypeGrant> types = List.of();
    private List<RoleGrant> roles = List.of();

    public List<TypeGrant> getTypes() { return types; }
    public void setTypes(List<TypeGrant> types) { this.types = types; }

    public List<RoleGrant> getRoles() { return roles; }
    public void setRoles(List<RoleGrant> roles) { this.roles = roles; }

    public static class TypeGrant {
        private long typeId;
        private DefaultAccess level;

        public long getTypeId() { return typeId; }
        public void setTypeId(long typeId) { this.typeId = typeId; }
        public DefaultAccess getLevel() { return level; }
        public void setLevel(DefaultAccess level) { this.level = level; }
    }

    public static class RoleGrant {
        private String name;
        private List<TypeGrant> grants = List.of();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<TypeGrant> getGrants() { return grants; }
        public void setGrants(List<TypeGrant> grants) { this.grants = grants; }
    }
}
