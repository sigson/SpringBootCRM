package app.springbootcrm.documents.activity;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.catalogs.leadsource.LeadSource;
import app.springbootcrm.catalogs.customer.Customer;
import app.springbootcrm.user.User;
import domain.core.access.BypassPolicy;
import domain.core.access.DefaultAccess;
import domain.core.access.UserClaim;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.AggregateReference;
import domain.core.ddd.AggregateReferenceUuidUserType;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.AccessFiltered;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import domain.core.ddd.annotations.ValidAggregateRef;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CompositeType;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие календаря (typeId=5001).
 *
 * <p><b>Это регистр</b> (наследует lean-корень {@link AbstractAggregate} напрямую),
 * а не справочник: версионирование, аудит и per-instance ACL ему не нужны — только
 * объявленные реквизиты (название, описание, интервал, владелец, связанный объект).
 *
 * <p><b>Row-level фильтрация.</b> {@link AccessFiltered} привязывает поле {@code owner}
 * к JWT-claim'у {@link UserClaim#SELF_IDS} (id текущего пользователя) и активирует
 * Hibernate-фильтр {@code filter_activity_owner} в каждом SELECT'е через
 * {@code AccessFilterActivator}. В итоге запрос получает {@code WHERE … AND owner_id IN (:ids)},
 * и пользователь видит только свои события. Администраторы (ROOT_READ) фильтр обходят —
 * это политика {@code AccessFilterActivator}, а не самого агрегата.
 *
 * <p>Поля {@code ownAccess} у регистра нет, поэтому instance-ограничений {@code AccessResolver}
 * не накладывает — видимость целиком определяется row-level фильтром.
 */
@Entity
@Table(name = "activities")
// READ_WRITE, а не READ_ONLY: активность — личная запись, её заводит себе любой
// аутентифицированный пользователь. Ограничения дают не флаги типа, а row-фильтр
// ниже (чужие строки не видны вовсе) и проверки владельца в ActivityService.
// При READ_ONLY обычный пользователь не мог создать даже собственную активность,
// и row-фильтр было нечего фильтровать.
@TypeId(value = Activity.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_WRITE)
@AccessFiltered(
        filterField = "owner",
        userClaim = UserClaim.SELF_IDS,
        referencedTypeId = User.TYPE_ID,
        filterName = "filter_activity_owner",
        bypassPolicy = BypassPolicy.EXPLICIT_ONLY
)
@AccessChecked(strict = true)
@UiAggregate(
        slug = "activities",
        singularLabel = "Activity",
        pluralLabel = "Activities",
        iconHint = "📅",
        displayPattern = "{title}",
        apiBase = "/api/activities",
        userCreatable = true,
        isReference = false
)
@FilterDef(
        name = "filter_activity_owner",
        parameters = @ParamDef(name = "ids", type = UUID.class)
)
@Filter(
        name = "filter_activity_owner",
        condition = "owner_id in (:ids)"
)
public class Activity extends AbstractAggregate<UUID> {

    public static final long TYPE_ID = 5001L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @FieldId(value = 5001_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Title", order = 10, required = true)
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @FieldId(value = 5001_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Description", kind = UiField.UiFieldKind.TEXTAREA, order = 20)
    @Column(name = "description", length = 2000)
    private String description;

    @FieldId(value = 5001_12L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Starts at", order = 30, required = true)
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @FieldId(value = 5001_13L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Ends at", order = 40, required = true)
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /**
     * Владелец события. По умолчанию проставляется сервисом при создании (из id текущего
     * пользователя), но это полноценный редактируемый ссылочный реквизит (READ_WRITE):
     * перепривязку контролирует {@code ActivityService} — администратор может назначить
     * любого пользователя, обычный — только себя. Это же поле служит target'ом для
     * {@link AccessFiltered}: видимость события определяется текущим {@code owner_id}.
     */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "owner_type_id", nullable = false)),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "owner_id", nullable = false))
    })
    @ValidAggregateRef(targets = {User.class}, idType = UUID.class)
    @FieldId(value = 5001_14L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Owner", kind = UiField.UiFieldKind.REF, order = 50,
            description = "Activity owner. An administrator may assign anyone; "
                    + "a regular user may assign only themselves. Left empty on create - the current user.")
    private AggregateReference<User, UUID> owner;

    /**
     * Связанный объект события — полиморфная ссылка на один из типов {@link User},
     * {@link Customer} или {@link LeadSource}. В отличие от {@link #owner}:
     * <ul>
     *   <li>необязательная (nullable) — событие может не иметь связанного объекта;</li>
     *   <li>не участвует в row-level фильтре {@code SELF_IDS}, поэтому не влияет на видимость;</li>
     *   <li>все цели с UUID-id, поэтому хранится тем же {@link AggregateReferenceUuidUserType}
     *       в колонках {@code subject_type_id} + {@code subject_id}; конкретный тип строки
     *       задаёт {@code subject_type_id}.</li>
     * </ul>
     */
    @Embedded
    @CompositeType(AggregateReferenceUuidUserType.class)
    @AttributeOverrides({
            @AttributeOverride(name = "targetTypeId",
                    column = @Column(name = "subject_type_id")),
            @AttributeOverride(name = "targetIdRaw",
                    column = @Column(name = "subject_id"))
    })
    @ValidAggregateRef(
            targets = {User.class, Customer.class, LeadSource.class},
            idType = UUID.class)
    @FieldId(value = 5001_15L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Related object", kind = UiField.UiFieldKind.REF, order = 60)
    private AggregateReference<?, ?> subject;

    public Activity() {}

    public Activity(UUID id, String title, String description,
                         Instant startsAt, Instant endsAt,
                         AggregateReference<User, UUID> owner) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.owner = owner;
    }

    @Override public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public AggregateReference<User, UUID> getOwner() { return owner; }

    public AggregateReference<?, ?> getSubject() { return subject; }

    public void setTitle(String t) { this.title = t; }
    public void setDescription(String d) { this.description = d; }
    public void setStartsAt(Instant s) { this.startsAt = s; }
    public void setEndsAt(Instant e) { this.endsAt = e; }

    public void setOwner(AggregateReference<User, UUID> owner) { this.owner = owner; }

    public void setOwnerId(UUID userId) {
        this.owner = AggregateReference.<User, UUID>ofRaw(User.TYPE_ID, userId.toString());
    }

    public void setSubject(AggregateReference<?, ?> s) { this.subject = s; }

    /** Назначить связанный объект по паре (typeId, uuid); null в любом аргументе снимает ссылку. */
    public void setSubjectRef(Long typeId, UUID id) {
        if (typeId == null || id == null) { this.subject = null; return; }
        this.subject = AggregateReference.ofRaw(typeId, id.toString());
    }
}
