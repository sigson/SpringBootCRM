package app.springbootcrm.bootstrap;

import app.springbootcrm.reference.CodeGenerator;
import app.springbootcrm.user.User;
import app.springbootcrm.user.UserRepository;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessFlags;
import domain.core.access.AccessMetric;
import domain.core.access.SystemAccessContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.UUID;

/**
 * При первом запуске (пустая БД) создаёт администратора, выделяя справочный код
 * через {@link CodeGenerator}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5000)
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    public static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AccessContextHolder holder;
    private final SystemAccessContexts systems;
    private final CodeGenerator codeGen;

    @Value("${app.bootstrap.admin.username:admin}")
    private String adminUsername;

    @Value("${app.bootstrap.admin.password:admin}")
    private String adminPassword;

    @Value("${app.bootstrap.admin.email:admin@springbootcrm.local}")
    private String adminEmail;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          AccessContextHolder holder, SystemAccessContexts systems,
                          CodeGenerator codeGen) {
        this.users = users;
        this.encoder = encoder;
        this.holder = holder;
        this.systems = systems;
        this.codeGen = codeGen;
    }

    @Override
    public void run(String... args) throws Exception {
        try (var ignored = holder.bind(systems.maxPrivileges())) {
            populate();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void populate() {
        if (users.count() > 0) {
            log.info("AdminBootstrap: users already exist - skipping admin creation.");
            return;
        }

        AccessMetric rootMetric = AccessMetric.empty()
                .withGlobalFlags(AccessFlags.ROOT_WRITE);

        String code = codeGen.nextFor(User.class, c -> users.findByCode(c).isEmpty());
        User admin = new User(
                ADMIN_ID,
                code,
                "System administrator",
                adminUsername,
                adminEmail,
                "System administrator",
                encoder.encode(adminPassword),
                true,
                rootMetric
        );
        users.save(admin);

        log.info("==========================================================");
        log.info("AdminBootstrap: created admin '{}' (id={}, code={}, ROOT_WRITE)",
                adminUsername, ADMIN_ID, code);
        log.info("WARNING: change the password from the UI after the first sign-in!");
        log.info("==========================================================");
    }
}
