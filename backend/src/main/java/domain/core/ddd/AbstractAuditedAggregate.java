package domain.core.ddd;

import domain.core.access.AccessMetric;
import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.FieldId;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.MappedSuperclass;

import java.io.Serializable;

/**
 * База для бизнес-агрегатов, которым нужен <b>per-instance ACL</b> ({@code ownAccess})
 * поверх версии и аудита.
 *
 * <p>Расширяет {@link AbstractAuditedNoAclAggregate}, добавляя единственное поле
 * {@code ownAccess} ({@code @FieldId(1)}, READ_ONLY). Наследуют только агрегаты, где
 * построчные права осмысленны: субъекты доступа ({@link UserAggregate}, роли) и доменные
 * документы. Справочники остаются на {@link AbstractAuditedNoAclAggregate}.
 *
 * <p>Field ID 1 объявлен на этом абстрактном супертипе ({@code MetadataBootstrapper}
 * освобождает абстрактные классы от namespacing'а); наследники его не переопределяют.
 */
@MappedSuperclass
public abstract class AbstractAuditedAggregate<ID extends Serializable>
        extends AbstractAuditedNoAclAggregate<ID> {

    /** Per-instance ACL. {@link AccessMetric} полностью иммутабелен; {@link #setOwnAccess} заменяет ссылку. */
    @Embedded
    @AttributeOverride(name = "payload", column = @Column(name = "own_access"))
    @FieldId(value = 1, defaultAccess = DefaultAccess.READ_ONLY)
    private AccessMetric ownAccess = AccessMetric.empty();

    @Override
    public AccessMetric ownAccess() {
        return ownAccess == null ? AccessMetric.empty() : ownAccess;
    }

    public void setOwnAccess(AccessMetric m) {
        this.ownAccess = (m == null) ? AccessMetric.empty() : m;
    }
}
