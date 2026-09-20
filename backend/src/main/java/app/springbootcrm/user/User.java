package app.springbootcrm.user;

import app.springbootcrm.reference.AbstractReferenceAggregate;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.interfaces.InterfaceLayout;
import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.Reference;
import app.springbootcrm.reference.ReferenceAggregate;
import domain.core.access.AccessMetric;
import domain.core.access.DefaultAccess;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.AggregateReferenceUuidUserType;
import domain.core.ddd.UserAggregate;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.AggregateLockingPolicy;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import domain.core.ddd.annotations.ValidAggregateRef;
import domain.core.validation.CharPattern;
import domain.core.validation.EmailFormat;
import domain.core.validation.Required;
import domain.core.validation.TextLength;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CompositeType;

import java.util.UUID;

/**
 * Центральный агрегат-пользователь (typeId=9001).
 *
 * <p>Пользователь — элемент справочника: имеет {@code code} (READ_ONLY, префикс «USR»,
 * генерируется автоматически) и {@code name} (READ_WRITE). {@code displayName} — отдельное
 * полное имя; {@code username} — логин для аутентификации (уникальный, READ_ONLY после создания).
 *
 * <p>Наследует {@code UserAggregate<UUID>} (нужно для системы доступа), а не
 * {@code AbstractReferenceAggregate}, поэтому code/name объявлены здесь с FieldId
 * в namespace 9001 (900101, 900102): MetadataBootstrapper требует, чтобы поля
 * конкретного класса лежали в диапазоне [typeId*100; typeId*100+99].
 */
@Entity
@Table(name = "users")
@TypeId(value = 9001L, defaultRepoAccess = DefaultAccess.READ_WRITE)
@Reference(prefix = "USR", codeWidth = 8, singularName = "user")
@AccessChecked(strict = true)
@AggregateLockingPolicy(AggregateLockingPolicy.LockMode.OPTIMISTIC_FORCE_INCREMENT)
@UiAggregate(
        slug = "users",
        singularLabel = "User",
        pluralLabel = "Users",
        iconHint = "👥",
        displayPattern = "{code} — {name}",
        apiBase = "/api/users",
        userCreatable = false,
        isReference = true,
        passwordField = "Password"
)
public class User extends UserAggregate<UUID>
        implements ReferenceAggregate {

    public static final long TYPE_ID = 9001L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @FieldId(value = 9001_01L, defaultAccess = DefaultAccess.INIT_ONCE)
    @UiField(label = "Code", kind = UiField.UiFieldKind.CODE, order = 5, required = true)
    @Required
    @TextLength(max = 50)
    @CharPattern(regex = "^[A-Za-z\\u0400-\\u04FF0-9._/-]+$",
                 message = "Code may contain only letters, digits and . _ - /")
    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @FieldId(value = 9001_02L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Name", order = 6, required = true)
    @Required
    @TextLength(max = 200)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @FieldId(value = 9001_10L, defaultAccess = DefaultAccess.INIT_ONCE)
    @UiField(label = "Username", order = 10, required = true)
    @Required
    @TextLength(min = 3, max = 100, message = "Username: 3 to 100 characters")
    @CharPattern(regex = "^[A-Za-z0-9._-]+$",
                 message = "Username may contain only Latin letters, digits and . _ -")
    @Column(name = "username", nullable = false, length = 100, unique = true)
    private String username;

    @FieldId(value = 9001_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Email", kind = UiField.UiFieldKind.EMAIL, order = 20)
    @EmailFormat
    @Column(name = "email", length = 200)
    private String email;

    @FieldId(value = 9001_12L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Full name", order = 30)
    @Column(name = "display_name", length = 200)
    private String displayName;

    @FieldId(value = 9001_13L, defaultAccess = DefaultAccess.HIDDEN)
    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @FieldId(value = 9001_14L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Active", order = 40)
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Embedded
    @AttributeOverride(name = "payload", column = @Column(name = "user_access"))
    @FieldId(value = 9001_15L, defaultAccess = DefaultAccess.READ_ONLY)
    private AccessMetric access = AccessMetric.empty();

    /**
     * Роль доступа пользователя — одна ссылка {@link AggregateReference} на {@link AccessRole}
     * в колонках {@code role_type_id} + {@code role_id}. Nullable: пользователь может не иметь
     * роли (тогда его {@code access} равен {@link AccessMetric#empty()}). Показ и редактирование
     * в форме ограничены администратором на уровне UI/сервиса.
     */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "role_type_id")),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "role_id"))
    })
    @ValidAggregateRef(targets = {AccessRole.class}, idType = UUID.class)
    @FieldId(value = 9001_16L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Access role", kind = UiField.UiFieldKind.REF, order = 50)
    private AggregateReference<AccessRole, UUID> role;

    /**
     * Назначенный интерфейс (справочник {@link InterfaceLayout}, typeId=9300).
     * {@code null} → пользователь получает сгенерированный режим навигации по умолчанию.
     */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "interface_layout_type_id")),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "interface_layout_id"))
    })
    @ValidAggregateRef(targets = {InterfaceLayout.class}, idType = UUID.class)
    @FieldId(value = 9001_17L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Interface", kind = UiField.UiFieldKind.REF, order = 60, hiddenInTable = true)
    private AggregateReference<InterfaceLayout, UUID> interfaceLayout;

    public User() {}

    public User(UUID id, String code, String name,
                String username, String email, String displayName,
                String passwordHash, boolean enabled, AccessMetric access) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.access = access == null ? AccessMetric.empty() : access;
    }

    @Override public UUID getId() { return id; }

    public String getCode()          { return code; }
    public String getName()          { return name; }
    public String getUsername()      { return username; }
    public String getEmail()         { return email; }
    public String getDisplayName()   { return displayName; }
    public String getPasswordHash()  { return passwordHash; }
    public boolean isEnabled()       { return enabled; }

    public AggregateReference<AccessRole, UUID> getRole() { return role; }

    public UUID getRoleId() {
        if (role == null || role.targetIdRaw() == null) return null;
        try { return UUID.fromString(role.targetIdRaw()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public void setName(String n)         { this.name = n; }
    public void setEmail(String e)        { this.email = e; }
    public void setDisplayName(String n)  { this.displayName = n; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    public void setEnabled(boolean e)     { this.enabled = e; }

    public void setRole(AggregateReference<AccessRole, UUID> r) { this.role = r; }

    public void setRoleId(UUID roleId) {
        this.role = (roleId == null)
                ? null
                : AggregateReference.ofRaw(AccessRole.TYPE_ID, roleId.toString());
    }

    public AggregateReference<InterfaceLayout, UUID> getInterfaceLayout() { return interfaceLayout; }

    public UUID getInterfaceLayoutId() {
        if (interfaceLayout == null || interfaceLayout.targetIdRaw() == null) return null;
        try { return UUID.fromString(interfaceLayout.targetIdRaw()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public void setInterfaceLayout(AggregateReference<InterfaceLayout, UUID> r) {
        this.interfaceLayout = r;
    }

    public void setInterfaceLayoutId(UUID id) {
        this.interfaceLayout = (id == null)
                ? null
                : AggregateReference.ofRaw(InterfaceLayout.TYPE_ID, id.toString());
    }

    @Override public AccessMetric getAccess() { return access; }
    @Override public void setAccess(AccessMetric a) {
        this.access = a == null ? AccessMetric.empty() : a;
    }
}
