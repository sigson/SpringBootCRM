package domain.core.persistence;

import java.io.Closeable;

/**
 * Режим работы {@code PostLoadAccessCheckListener}'а.
 *
 * <ul>
 *   <li>{@link #THROW} — для single-row контрактов ({@code findById}, ...) — кидает
 *       {@code AccessDeniedException} при cross-tenant load'е.</li>
 *   <li>{@link #MARK_AND_DROP} — для коллекций — помечает entity в {@code PostLoadDropMarker};
 *       результаты потом отфильтровываются на уровне {@code Page}/итератора.</li>
 * </ul>
 *
 * <p>Режим устанавливается AOP-аспектом {@code AccessFilterActivator}'ом через
 * {@link PostLoadModeHolder} per-call.
 *
 */
public enum PostLoadMode {
    THROW,
    MARK_AND_DROP
}
