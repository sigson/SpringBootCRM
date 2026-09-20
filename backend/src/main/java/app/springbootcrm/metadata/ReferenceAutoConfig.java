package app.springbootcrm.metadata;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Реєструє {@link ReferenceProviderAutoRegistrar} — хук авто-реєстрації
 * довідкових провайдерів.
 *
 * <p>Метод-фабрика {@code static}: {@link ReferenceProviderAutoRegistrar} —
 * {@code BeanDefinitionRegistryPostProcessor}, тож має створюватися гранично рано,
 * до повної ініціалізації конфіг-класу. {@code static @Bean} гарантує, що Spring
 * не змушений інстанціювати решту конфігурації заради цього біна.
 */
@Configuration(proxyBeanMethods = false)
public class ReferenceAutoConfig {

    @Bean
    static ReferenceProviderAutoRegistrar referenceProviderAutoRegistrar() {
        return new ReferenceProviderAutoRegistrar();
    }
}
