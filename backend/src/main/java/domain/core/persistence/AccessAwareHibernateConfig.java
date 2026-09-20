package domain.core.persistence;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * Регистрация {@code AccessAware}-listener'ов через {@code IntegratorProvider}.
 *
 * <p>Через {@code hibernate.integrator_provider} мы передаём {@link Integrator}, который
 * вызывается ровно в момент {@code SessionFactory#integrate()} — корректная точка регистрации.
 *
 * <p>Зарегистрированные listener'ы:
 * <ul>
 *   <li>{@link AccessAwarePreInsertListener} — repo + field check на INSERT;</li>
 *   <li>{@link AccessAwarePreUpdateListener} — repo + field check на UPDATE (симметрично PreInsert);</li>
 *   <li>{@link AccessAwarePreDeleteListener} — repo-level check на DELETE;</li>
 *   <li>{@link PostLoadAccessCheckListener} — backstop для cross-tenant find.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AccessAwareHibernateConfig {

    @Bean
    public AccessAwarePreUpdateListener accessAwarePreUpdateListener(
            ObjectProvider<domain.core.access.AccessResolver> resolver,
            ObjectProvider<domain.core.bootstrap.MetadataSnapshotProvider> snapshots,
            ObjectProvider<domain.core.access.AccessContextHolder> holder) {
        return new AccessAwarePreUpdateListener(resolver, snapshots, holder);
    }

    @Bean
    public AccessAwarePreInsertListener accessAwarePreInsertListener(
            ObjectProvider<domain.core.access.AccessResolver> resolver,
            ObjectProvider<domain.core.bootstrap.MetadataSnapshotProvider> snapshots,
            ObjectProvider<domain.core.access.AccessContextHolder> holder) {
        return new AccessAwarePreInsertListener(resolver, snapshots, holder);
    }

    @Bean
    public AccessAwarePreDeleteListener accessAwarePreDeleteListener(
            ObjectProvider<domain.core.access.AccessResolver> resolver,
            ObjectProvider<domain.core.bootstrap.MetadataSnapshotProvider> snapshots,
            ObjectProvider<domain.core.access.AccessContextHolder> holder) {
        return new AccessAwarePreDeleteListener(resolver, snapshots, holder);
    }

    @Bean
    public PostLoadAccessCheckListener postLoadAccessCheckListener(
            ObjectProvider<domain.core.bootstrap.MetadataSnapshotProvider> snapshots,
            ObjectProvider<domain.core.access.AccessContextHolder> holder,
            ObjectProvider<domain.core.access.ClaimsExtractor> claims,
            ObjectProvider<domain.core.access.AccessResolver> resolver,
            ObjectProvider<PostLoadDropMarker> markerProvider) {
        return new PostLoadAccessCheckListener(snapshots, holder, claims, resolver, markerProvider);
    }

    @Bean
    public HibernatePropertiesCustomizer accessAwareHibernateCustomizer(
            ObjectProvider<AccessAwarePreUpdateListener> preUpdate,
            ObjectProvider<AccessAwarePreInsertListener> preInsert,
            ObjectProvider<AccessAwarePreDeleteListener> preDelete,
            ObjectProvider<PostLoadAccessCheckListener> postLoad) {

        return props -> {
            org.hibernate.jpa.boot.spi.IntegratorProvider provider = () ->
                    Collections.singletonList(new AccessIntegrator(preUpdate, preInsert, preDelete, postLoad));
            props.put("hibernate.integrator_provider", provider);
        };
    }

    private static final class AccessIntegrator implements Integrator {

        private final ObjectProvider<AccessAwarePreUpdateListener> preUpdate;
        private final ObjectProvider<AccessAwarePreInsertListener> preInsert;
        private final ObjectProvider<AccessAwarePreDeleteListener> preDelete;
        private final ObjectProvider<PostLoadAccessCheckListener> postLoad;

        AccessIntegrator(ObjectProvider<AccessAwarePreUpdateListener> preUpdate,
                         ObjectProvider<AccessAwarePreInsertListener> preInsert,
                         ObjectProvider<AccessAwarePreDeleteListener> preDelete,
                         ObjectProvider<PostLoadAccessCheckListener> postLoad) {
            this.preUpdate = preUpdate;
            this.preInsert = preInsert;
            this.preDelete = preDelete;
            this.postLoad = postLoad;
        }

        @Override
        public void integrate(Metadata metadata,
                              BootstrapContext bootstrapContext,
                              SessionFactoryImplementor sessionFactory) {
            EventListenerRegistry reg = sessionFactory.getServiceRegistry()
                    .getService(EventListenerRegistry.class);

            AccessAwarePreUpdateListener pu = preUpdate.getIfAvailable();
            if (pu != null) reg.appendListeners(EventType.PRE_UPDATE, pu);

            AccessAwarePreInsertListener pi = preInsert.getIfAvailable();
            if (pi != null) reg.appendListeners(EventType.PRE_INSERT, pi);

            AccessAwarePreDeleteListener pd = preDelete.getIfAvailable();
            if (pd != null) reg.appendListeners(EventType.PRE_DELETE, pd);

            PostLoadAccessCheckListener pl = postLoad.getIfAvailable();
            if (pl != null) reg.appendListeners(EventType.POST_LOAD, pl);
        }

        @Override
        public void disintegrate(SessionFactoryImplementor sessionFactory,
                                  SessionFactoryServiceRegistry serviceRegistry) {
            // no-op
        }
    }
}
