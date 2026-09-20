package domain.core.web;

import app.springbootcrm.user.UserDto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import domain.core.access.AccessContextHolder;
import domain.core.access.AccessResolver;
import domain.core.bootstrap.MetadataSnapshotProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрирует {@link AccessAwareSerializerModifier} в primary {@link ObjectMapper}'е,
 * который Spring Boot использует для сериализации REST-ответов.
 *
 * <p>Это покрывает все {@code @RestController}-endpoint'ы автоматически — без участия
 * app-кода. DTO-проекции вроде {@code UserDto.basic()/full()} становятся не нужны:
 * чувствительные поля скрываются на основании {@code @FieldId(defaultAccess=…)} + текущего
 * {@link domain.core.access.AccessContext} от пользователя.
 *
 * <p><b>Обратите внимание:</b> outbox-канал в production'е должен использовать ОТДЕЛЬНЫЙ
 * {@code ObjectMapper} БЕЗ этого модификатора (см. {@code outboxObjectMapper} в design'е),
 * чтобы payload в outbox содержал все поля «как есть». В текущей реализации outbox-канал
 * не используется в app.springbootcrm, поэтому отдельный mapper не зарегистрирован.
 */
@Configuration(proxyBeanMethods = false)
public class AccessAwareObjectMapperConfig {

    /**
     * Customizer кастомизирует BUILDER primary mapper'а — это самый ранний хук,
     * до того как Spring добавит свои дефолты. Регистрируем {@link AccessAwareSerializerModifier}
     * через {@link SimpleModule}, чтобы он попал в final-mapper.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer accessAwareCustomizer(
            MetadataSnapshotProvider snapshots,
            AccessContextHolder holder,
            AccessResolver resolver) {

        return builder -> builder.postConfigurer(mapper -> {
            SimpleModule module = new SimpleModule("AccessAwareSerializerModifier");
            module.setSerializerModifier(
                    new AccessAwareSerializerModifier(snapshots, holder, resolver, /* maskWithNull */ true));
            mapper.registerModule(module);
        });
    }
}
