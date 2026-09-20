package domain.core.access;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Иммутабельный payload для {@link AccessMetric}.
 *
 * <p>Структура:
 * <ul>
 *   <li>{@code globalFlags} — глобальные права (применимы ко всем типам);</li>
 *   <li>{@code typeFlags} — per-type права (typeId → flags); сюда включаются и plain-биты
 *       ({@code WRITE_INSERT}/{@code WRITE_UPDATE}/{@code READ}), и админ/root-bypass-биты.
 *       Единственный канал «дать пользователю что-то на конкретный тип»;</li>
 *   <li>{@code instanceWriteAcl} — explicit per-instance whitelist (typeId → set of ids).</li>
 * </ul>
 *
 * <p>{@link Map#copyOf(Map)} в compact-конструкторе обеспечивает иммутабельность
 * даже при модификации caller'ом переданной map'ы.
 */
public record AccessMetricPayload(
        int globalFlags,
        Map<Long, Integer> typeFlags,
        Map<Long, Set<String>> instanceWriteAcl
) {

    @JsonCreator
    public AccessMetricPayload(
            @JsonProperty("globalFlags") int globalFlags,
            @JsonProperty("typeFlags") Map<Long, Integer> typeFlags,
            @JsonProperty("instanceWriteAcl") Map<Long, Set<String>> instanceWriteAcl) {
        this.globalFlags      = globalFlags;
        this.typeFlags        = (typeFlags == null) ? Map.of() : Map.copyOf(typeFlags);
        this.instanceWriteAcl = deepCopyInstanceAcl(instanceWriteAcl);
    }

    public static AccessMetricPayload empty() {
        return new AccessMetricPayload(0, Map.of(), Map.of());
    }

    public AccessMetricPayload addTypeFlags(long typeId, int flags) {
        Map<Long, Integer> next = new HashMap<>(typeFlags);
        next.merge(typeId, flags, (a, b) -> a | b);
        return new AccessMetricPayload(globalFlags, Map.copyOf(next), instanceWriteAcl);
    }

    public AccessMetricPayload addGlobalFlags(int flags) {
        return new AccessMetricPayload(globalFlags | flags, typeFlags, instanceWriteAcl);
    }

    public AccessMetricPayload addInstanceWhitelist(long typeId, String instanceId) {
        Map<Long, Set<String>> next = new HashMap<>(instanceWriteAcl);
        next.merge(typeId, Set.of(instanceId), (a, b) -> {
            Set<String> u = new HashSet<>(a);
            u.addAll(b);
            return Set.copyOf(u);
        });
        return new AccessMetricPayload(globalFlags, typeFlags, Map.copyOf(next));
    }

    /**
     * Поэлементный union двух payload'ов: OR глобальных и type-flag'ов, объединение
     * множеств в instanceWriteAcl. Каноническая «склейка двух наборов прав».
     */
    public AccessMetricPayload union(AccessMetricPayload other) {
        if (other == null) return this;
        int g = this.globalFlags | other.globalFlags;

        Map<Long, Integer> tf = new HashMap<>(this.typeFlags);
        for (var e : other.typeFlags.entrySet()) {
            tf.merge(e.getKey(), e.getValue(), (a, b) -> a | b);
        }

        Map<Long, Set<String>> wl = new HashMap<>();
        for (var e : this.instanceWriteAcl.entrySet()) {
            wl.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (var e : other.instanceWriteAcl.entrySet()) {
            wl.merge(e.getKey(), new HashSet<>(e.getValue()), (a, b) -> {
                Set<String> u = new HashSet<>(a);
                u.addAll(b);
                return u;
            });
        }
        // Иммутабилизируем
        Map<Long, Set<String>> wlFinal = new HashMap<>();
        wl.forEach((k, v) -> wlFinal.put(k, Set.copyOf(v)));

        return new AccessMetricPayload(g, Map.copyOf(tf), Map.copyOf(wlFinal));
    }

    private static Map<Long, Set<String>> deepCopyInstanceAcl(Map<Long, Set<String>> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<Long, Set<String>> out = new HashMap<>(in.size());
        for (var e : in.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? Set.of() : Set.copyOf(e.getValue()));
        }
        return Map.copyOf(out);
    }
}
