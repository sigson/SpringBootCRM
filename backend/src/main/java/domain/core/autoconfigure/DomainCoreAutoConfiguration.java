package domain.core.autoconfigure;

import domain.core.access.GrantsProperties;
import domain.core.bootstrap.BootstrapProperties;
import domain.core.bootstrap.MetadataBootstrapper;
import domain.core.bootstrap.MetadataSnapshotProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration для DDD-core'а.
 *
 * <p>{@code @AutoConfigureBefore(HibernateJpaAutoConfiguration.class)} критично:
 * {@link MetadataBootstrapper} должен построить snapshot ДО создания
 * {@code EntityManagerFactory} (его читают access-listener'ы и
 * {@code SystemAccessContexts.@PostConstruct}). Bootstrapper — не {@code @Component},
 * а {@code @Bean} с {@code initMethod="run"}.
 *
 * <h3>JPA scan: почему {@link AutoConfigurationPackage}, а не {@code @EntityScan}</h3>
 *
 * <p>Фреймворк владеет собственными JPA-артефактами ({@code OutboxEntity},
 * {@code OutboxJpaRepository}, {@code AggregateReferenceXxxState}) вне пакета
 * пользовательского {@code @SpringBootApplication}, и их скан нужно расширить, не сломав
 * дефолтный скан приложения. Ни {@code @EnableJpaRepositories} (отключает Boot'ову
 * {@code JpaRepositoriesAutoConfiguration}), ни {@code @EntityScan} (вытесняет
 * {@code AutoConfigurationPackages}-fallback, и пакет приложения теряется) для этого не годятся.
 * {@link AutoConfigurationPackage} с явными {@code basePackages} аддитивен: пакеты мерджатся,
 * поэтому Hibernate и Spring Data видят и {@code app.*}, и {@code domain.core.ddd}/{@code .eventing}
 * (см. <a href="https://github.com/spring-projects/spring-boot/issues/32079">spring-boot#32079</a>).
 */
@AutoConfiguration
@AutoConfigureBefore(HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(name = "org.hibernate.SessionFactory")
@ComponentScan(basePackages = {
        "domain.core.access",
        "domain.core.audit",
        "domain.core.bootstrap",
        "domain.core.context",
        "domain.core.ddd",
        "domain.core.eventing",
        "domain.core.persistence",
        "domain.core.web"
})
@AutoConfigurationPackage(basePackages = {
        "domain.core.ddd",
        "domain.core.eventing"
})
@EnableConfigurationProperties({BootstrapProperties.class, GrantsProperties.class})
public class DomainCoreAutoConfiguration {

    @Bean(initMethod = "run")
    @ConditionalOnMissingBean
    public MetadataBootstrapper metadataBootstrapper(MetadataSnapshotProvider provider,
                                                      BootstrapProperties props,
                                                      ObjectProvider<GrantsProperties> grantsProvider) {
        GrantsProperties grants = grantsProvider.getIfAvailable(GrantsProperties::new);
        return new MetadataBootstrapper(provider, props, grants);
    }
}
