package app.springbootcrm.access;

import app.springbootcrm.user.User;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.Reference;
import app.springbootcrm.reference.ReferenceAggregate;
import domain.core.access.AccessMetric;
import domain.core.access.DefaultAccess;
import domain.core.ddd.AbstractAuditedAggregate;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Агрегат-пресет набора прав, собираемый администратором.
 *
 * <p>typeId=9100. {@code defaultRepoAccess=READ_ONLY} — все аутентифицированные
 * пользователи могут читать роли (видеть свои собственные), но изменять —
 * только администраторы (через ADMIN_WRITE | ROOT_WRITE на typeId 9100).
 *
 * <p>{@code accessTemplate} — это сериализованный {@link AccessMetric}, который при
 * назначении мерджится в {@code User.access}. Так роль становится «материализованным»
 * набором прав, не требующим дополнительных round-trip'ов при каждом access-check'е.
 */
@Entity
@Table(name = "access_roles")
@TypeId(value = AccessRole.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "ROL", codeWidth = 8, singularName = "access role")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "access-roles",
        singularLabel = "Access role",
        pluralLabel = "Access roles",
        iconHint = "🔑",
        displayPattern = "{code} — {name}",
        apiBase = "/api/access-roles",
        userCreatable = false,
        isReference = true
)
public class AccessRole extends AbstractAuditedAggregate<UUID>
        implements ReferenceAggregate {

    public static final long TYPE_ID = 9100L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Стабильный человекочитаемый код роли (UPPERCASE), например {@code "MANAGER"}. */
    @FieldId(value = 9100_10L, defaultAccess = DefaultAccess.INIT_ONCE)
    @UiField(label = "Code", kind = UiField.UiFieldKind.CODE, order = 10, required = true)
    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @FieldId(value = 9100_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Name", order = 20, required = true)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @FieldId(value = 9100_12L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Description", kind = UiField.UiFieldKind.TEXTAREA, order = 30)
    @Column(name = "description", length = 500)
    private String description;

    /**
     * Шаблон прав. При назначении роли пользователю — мерджится в {@code User.access}.
     * READ_WRITE: модифицируется через admin-only API.
     */
    @Embedded
    @AttributeOverride(name = "payload", column = @Column(name = "access_template", nullable = false))
    @FieldId(value = 9100_13L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Permission preset", hiddenInTable = true, hiddenInForm = true, order = 40)
    private AccessMetric accessTemplate = AccessMetric.empty();

    @FieldId(value = 9100_14L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Active", order = 50)
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public AccessRole() {}

    public AccessRole(UUID id, String code, String name, String description,
                      AccessMetric accessTemplate, boolean enabled) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.accessTemplate = accessTemplate == null ? AccessMetric.empty() : accessTemplate;
        this.enabled = enabled;
    }

    @Override public UUID getId() { return id; }
    public String getCode()         { return code; }
    public String getName()         { return name; }
    public String getDescription()  { return description; }
    public AccessMetric getAccessTemplate() { return accessTemplate; }
    public boolean isEnabled()      { return enabled; }

    public void setName(String n)        { this.name = n; }
    public void setDescription(String d) { this.description = d; }
    public void setAccessTemplate(AccessMetric t) {
        this.accessTemplate = t == null ? AccessMetric.empty() : t;
    }
    public void setEnabled(boolean e)    { this.enabled = e; }
}
