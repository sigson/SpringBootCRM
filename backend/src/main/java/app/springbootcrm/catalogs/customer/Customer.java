package app.springbootcrm.catalogs.customer;

import app.springbootcrm.metadata.UiAggregate;
import app.springbootcrm.metadata.UiField;
import app.springbootcrm.reference.AbstractReferenceAggregate;
import app.springbootcrm.reference.Reference;
import domain.core.access.DefaultAccess;
import domain.core.ddd.annotations.AccessChecked;
import domain.core.ddd.annotations.FieldId;
import domain.core.ddd.annotations.TypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Customer catalog (typeId=4001).
 *
 * <p>defaultRepoAccess=READ_ONLY: any authenticated user may read; writing requires a
 * WRITE_INSERT/WRITE_UPDATE grant on typeId 4001, or admin rights.
 */
@Entity
@Table(name = "customers")
@TypeId(value = Customer.TYPE_ID, defaultRepoAccess = DefaultAccess.READ_ONLY)
@Reference(prefix = "CUS", codeWidth = 9, singularName = "customer")
@AccessChecked(strict = true)
@UiAggregate(
        slug = "customers",
        singularLabel = "Customer",
        pluralLabel = "Customers",
        iconHint = "🏢",
        displayPattern = "{code} — {name}",
        apiBase = "/api/customers",
        userCreatable = true,
        isReference = true
)
public class Customer extends AbstractReferenceAggregate {

    public static final long TYPE_ID = 4001L;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @FieldId(value = 4001_10L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Email", order = 30, maxLength = 160)
    @Column(name = "email", length = 160)
    private String email;

    @FieldId(value = 4001_11L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Phone", order = 40, maxLength = 40)
    @Column(name = "phone", length = 40)
    private String phone;

    @FieldId(value = 4001_12L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "City", order = 50, maxLength = 120)
    @Column(name = "city", length = 120)
    private String city;

    @FieldId(value = 4001_13L, defaultAccess = DefaultAccess.READ_WRITE)
    @UiField(label = "Notes", kind = UiField.UiFieldKind.TEXTAREA, order = 60, maxLength = 500)
    @Column(name = "notes", length = 500)
    private String notes;

    public Customer() {}

    public Customer(UUID id, String code, String name,
                    String email, String phone, String city, String notes) {
        super(code, name);
        this.id = id;
        this.email = email;
        this.phone = phone;
        this.city = city;
        this.notes = notes;
    }

    @Override public UUID getId() { return id; }

    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getCity()  { return city; }
    public String getNotes() { return notes; }

    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setCity(String city)   { this.city = city; }
    public void setNotes(String notes) { this.notes = notes; }
}
