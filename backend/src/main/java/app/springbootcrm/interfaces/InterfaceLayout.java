package app.springbootcrm.interfaces;

import app.springbootcrm.navigation.DefaultLayoutFactory;
import app.springbootcrm.navigation.NavigationService;
import app.springbootcrm.user.User;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.AbstractReferenceAggregate;
import app.springbootcrm.reference.Reference;
import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Справочник настраиваемых интерфейсов (typeId=9300).
 *
 * <p>{@code defaultRepoAccess=READ_ONLY}: читать может любой аутентифицированный пользователь
 * (чтобы {@code /api/navigation} прочитал назначенный ему интерфейс), создавать и редактировать —
 * только администратор.
 *
 * <p>Ключевой реквизит — {@code layout}: JSON-дерево {@link LayoutNode}, декларативно описывающее
 * навигацию пользователя, которому назначен интерфейс ({@code User.interfaceLayout}). JSON
 * редактируется визуальным конструктором, поэтому в generic-форме/таблице скрыт. Если интерфейс
 * не назначен, {@code NavigationService} отдаёт сгенерированный режим по умолчанию
 * ({@link app.springbootcrm.navigation.DefaultLayoutFactory}); видимость узлов в любом случае
 * дополнительно фильтруется по правам на сервере.
 */
@Entity
@Table(name = "interface_layouts")
@TypeId(value = InterfaceLayout.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "IFC", codeWidth = 6, singularName = "interface")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "interface-layouts",
        singularLabel = "Interface",
        pluralLabel = "Interfaces",
        iconHint = "🧩",
        displayPattern = "{code} — {name}",
        apiBase = "/api/interface-layouts",
        userCreatable = true,
        isReference = true
)
public class InterfaceLayout extends AbstractReferenceAggregate {

    public static final long TYPE_ID = 9300L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Дерево навигации (JSON). FieldId в namespace 9300; скрыто в generic-UI —
     * редактируется специализированным визуальным конструктором.
     */
    @FieldId(value = 9300_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Interface structure", kind = UiField.UiFieldKind.TEXTAREA,
            hiddenInTable = true, hiddenInForm = true, order = 30,
            description = "Navigation tree, edited with the visual builder")
    @Column(name = "layout_json", columnDefinition = "varchar(8000)")
    @Convert(converter = InterfaceLayoutJsonConverter.class)
    private List<LayoutNode> layout = new ArrayList<>();

    @FieldId(value = 9300_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Active", order = 40)
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public InterfaceLayout() {}

    public InterfaceLayout(UUID id, String code, String name,
                           List<LayoutNode> layout, boolean enabled) {
        super(code, name);
        this.id = id;
        this.layout = layout == null ? new ArrayList<>() : new ArrayList<>(layout);
        this.enabled = enabled;
    }

    @Override public UUID getId() { return id; }

    public List<LayoutNode> getLayout() {
        return layout == null ? List.of() : layout;
    }

    public void setLayout(List<LayoutNode> layout) {
        this.layout = layout == null ? new ArrayList<>() : new ArrayList<>(layout);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
