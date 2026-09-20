package app.springbootcrm.metadata;

import app.springbootcrm.access.AccessRole;
import app.springbootcrm.user.User;

import app.springbootcrm.reference.ReferenceAggregate;
import domain.core.ddd.AbstractAggregate;
import domain.core.ddd.annotations.TypeId;
import domain.core.persistence.RepositoryRegistry;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import jakarta.persistence.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * {@link BeanDefinitionRegistryPostProcessor}, що <b>автоматично</b> реєструє по одному
 * {@link GenericReferenceProvider} на кожен бізнес-об'єкт — без ручного списку.
 *
 * <p>Сканує classpath (тим самим {@link ClassGraph}, що й {@code MetadataBootstrapper}) і
 * відбирає нащадків {@link AbstractAggregate}, позначених {@code @TypeId} і {@code @Entity}.
 * Це покриває всі три родини: довідники ({@link ReferenceAggregate}, а також {@code User},
 * {@code AccessRole}), регістри ({@code AbstractAggregate} напряму) та табличні частини
 * ({@code AbstractTabularPart}). Щоб додати новий об'єкт, достатньо створити сутність та
 * репозиторій — провайдер з'явиться сам; код/найменування читаються через
 * {@link ReferenceAggregate}, регістри представляються через {@code displayPattern}.
 *
 * <p>Для нестандартної логіки оголосіть власний {@code @Component implements
 * ReferenceProvider<Type>}: {@link ReferenceResolver} віддасть перевагу рукописному над
 * згенерованим (див. {@link ReferenceProvider#generated()}), біни співіснують безконфліктно.
 */
public class ReferenceProviderAutoRegistrar
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ReferenceProviderAutoRegistrar.class);

    /** Дефолтні пакети сканування — збігаються з {@code MetadataBootstrapper}. */
    private List<String> scanPackages = List.of("domain", "app");

    @Override
    public void setEnvironment(Environment environment) {
        List<String> configured = environment.getProperty(
                "app.ddd.bootstrap.scan-packages", List.class);
        if (configured != null && !configured.isEmpty()) {
            this.scanPackages = configured;
        }
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
            throws BeansException {
        int registered = 0;
        try (ScanResult sr = new ClassGraph()
                .acceptPackages(scanPackages.toArray(String[]::new))
                .enableClassInfo()
                .enableAnnotationInfo()
                .scan()) {

            // Нащадки спільного кореня AbstractAggregate — усі три родини одразу
            // (довідники / регістри / табличні частини), без ручної реєстрації.
            for (ClassInfo ci : sr.getSubclasses(AbstractAggregate.class.getName())) {
                if (ci.isAbstract() || ci.isInterface()) continue;
                Class<?> cls = ci.loadClass();

                // Провайдер потрібен лише для персистентних, зареєстрованих агрегатів.
                if (!cls.isAnnotationPresent(Entity.class)) continue;
                TypeId typeIdAnn = cls.getAnnotation(TypeId.class);
                if (typeIdAnn == null) continue;
                long typeId = typeIdAnn.value();

                String beanName = "generatedReferenceProvider#" + typeId;
                if (registry.containsBeanDefinition(beanName)) continue;

                BeanDefinitionBuilder b = BeanDefinitionBuilder
                        .genericBeanDefinition(GenericReferenceProvider.class)
                        .addConstructorArgValue(typeId)
                        .addConstructorArgValue(cls)
                        .addConstructorArgValue(new RuntimeBeanReference(RepositoryRegistry.class));
                registry.registerBeanDefinition(beanName, b.getBeanDefinition());
                registered++;
                log.debug("Auto-registered GenericReferenceProvider for {} (typeId={})",
                        cls.getSimpleName(), typeId);
            }
        }
        log.info("ReferenceProviderAutoRegistrar: auto-registered {} provider(s) "
                + "across all aggregate families (references, registers, tabular parts)", registered);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        // нічого: уся робота — на етапі реєстрації визначень бінів вище.
    }

    /** Запускаємось після стандартного сканування компонентів. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
