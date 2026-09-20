package app.springbootcrm.reference;

/**
 * Бросается {@link CodeGenerator}'ом если на классе агрегата нет нужных аннотаций
 * ({@link Reference}, {@link domain.core.ddd.annotations.TypeId}) или auto-генерация
 * отключена ({@code codeWidth = 0}).
 */
public class UnknownReferenceException extends RuntimeException {

    public UnknownReferenceException(String message) {
        super(message);
    }
}
