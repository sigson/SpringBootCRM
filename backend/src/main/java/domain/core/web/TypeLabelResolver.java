package domain.core.web;

import app.springbootcrm.metadata.TypeRegistry;
import app.springbootcrm.metadata.TypeRegistryTypeLabelResolver;

/**
 * SPI для получения человекочитаемого имени типа по {@code typeId}.
 *
 * <p>Используется в {@link ErrorEnvelopeAdvice} для обогащения
 * {@link domain.core.access.PermissionRequirement} полем {@code typeLabel} перед
 * отправкой клиенту, чтобы показать имя типа вместо «typeId=4001».
 *
 * <p>Реализация — {@code app.springbootcrm.metadata.TypeRegistryTypeLabelResolver} (адаптер
 * над {@code TypeRegistry}). SPI вынесен в {@code domain.core}, чтобы не нарушать
 * layered-зависимость (domain.core не должен знать про app.springbootcrm). Если bean не
 * найден, обогащение пропускается, и {@code typeLabel} остаётся null.
 */
@FunctionalInterface
public interface TypeLabelResolver {
    /**
     * @return человекочитаемое имя типа или {@code null}, если тип неизвестен.
     */
    String labelFor(long typeId);
}
