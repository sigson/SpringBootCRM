package domain.core.bootstrap;

/**
 * Бросается из {@code MetadataBootstrapper} при нарушении любого fail-fast контракта.
 *
 * <p>Любая такая ошибка означает, что приложение НЕ должно стартовать —
 * это конфигурационный баг, который должен быть исправлен немедленно.
 */
public class BootstrapValidationException extends RuntimeException {

    public BootstrapValidationException(String message) {
        super(message);
    }

    public BootstrapValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
