package domain.core.persistence;

import java.io.Closeable;

/**
 * ThreadLocal-держатель текущего {@link PostLoadMode}, используемого
 * {@code PostLoadAccessCheckListener}'ом. Дефолт — {@link PostLoadMode#THROW}
 * (более безопасный fail-closed default).
 */
public final class PostLoadModeHolder {

    private PostLoadModeHolder() {}

    private static final ThreadLocal<PostLoadMode> CURRENT =
            ThreadLocal.withInitial(() -> PostLoadMode.THROW);

    public static PostLoadMode current() {
        return CURRENT.get();
    }

    public static Closeable bind(PostLoadMode mode) {
        PostLoadMode prev = CURRENT.get();
        CURRENT.set(mode);
        return () -> {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
        };
    }
}
