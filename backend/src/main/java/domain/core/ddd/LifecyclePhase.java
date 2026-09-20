package domain.core.ddd;

/** Фаза жизненного цикла агрегата. */
public enum LifecyclePhase {
    CREATE,
    UPDATE,
    DELETE,
    SOFT_DELETE
}
