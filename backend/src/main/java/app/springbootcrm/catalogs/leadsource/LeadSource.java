package app.springbootcrm.catalogs.leadsource;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.reference.AbstractReferenceAggregate;
import app.springbootcrm.reference.Reference;
import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Тип ремонта: текущий / средний / капитальный. Справочник READ_ONLY, typeId=4101.
 */
@Entity
@Table(name = "lead_sources")
@TypeId(value = LeadSource.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "SRC", codeWidth = 9, singularName = "lead source")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "lead-sources",
        singularLabel = "Lead source",
        pluralLabel = "Lead sources",
        iconHint = "📣",
        displayPattern = "{code} — {name}",
        apiBase = "/api/lead-sources",
        userCreatable = false,
        isReference = true
)
public class LeadSource extends AbstractReferenceAggregate {

    public static final long TYPE_ID = 4101L;

    @Id @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    public LeadSource() {}
    public LeadSource(UUID id, String code, String name) {
        super(code, name);
        this.id = id;
    }

    @Override public UUID getId() { return id; }
}
