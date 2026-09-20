package app.springbootcrm.user;

import app.springbootcrm.metadata.ReferenceProvider;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI-провайдер ссылок на User. Поиск идёт по коду / имени / логину / displayName /
 * email — поля выбора ссылок поддерживают ручной ввод кода или наименования.
 */
@Component
public class UserReferenceProvider implements ReferenceProvider<User> {

    private final UserRepository users;

    public UserReferenceProvider(UserRepository users) { this.users = users; }

    @Override public long typeId() { return User.TYPE_ID; }

    @Override
    public JpaSpecificationExecutor<User> filterRepository() { return users; }

    @Override
    public List<User> list(String query) {
        if (query == null || query.isBlank()) return users.findAll();
        String q = query.toLowerCase();
        return users.findAll().stream()
                .filter(u -> matches(u, q))
                .toList();
    }

    private boolean matches(User u, String q) {
        return contains(u.getCode(), q)
                || contains(u.getName(), q)
                || contains(u.getUsername(), q)
                || contains(u.getDisplayName(), q)
                || contains(u.getEmail(), q);
    }

    private boolean contains(String s, String q) {
        return s != null && s.toLowerCase().contains(q);
    }

    @Override
    public Optional<User> findById(String idRaw) {
        try { return users.findById(UUID.fromString(idRaw)); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
