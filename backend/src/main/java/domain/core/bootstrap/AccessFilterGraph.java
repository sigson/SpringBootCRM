package domain.core.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Граф достижимых через {@code AggregateReference}-поля typeId'ов.
 * Используется для транзитивного замыкания {@code BypassPolicy.AUTO_TRANSITIVE}.
 *
 */
public final class AccessFilterGraph {

    private final Map<Long, Set<Long>> reachable;

    private AccessFilterGraph(Map<Long, Set<Long>> r) {
        this.reachable = Map.copyOf(r);
    }

    /** TypeIds, достижимые из {@code startTypeId} рекурсивно. Не включает сам тип. */
    public long[] reachableFrom(long startTypeId) {
        Set<Long> r = reachable.getOrDefault(startTypeId, Set.of());
        return r.stream().mapToLong(Long::longValue).toArray();
    }

    /** Строит граф через FieldDescriptor.referencedTypeId() — без повторных findField'ов. */
    public static AccessFilterGraph build(Map<Long, AggregateDescriptor> aggregates) {
        // Direct edges: typeId → set of referenced typeIds
        Map<Long, Set<Long>> edges = new HashMap<>();
        for (AggregateDescriptor desc : aggregates.values()) {
            Set<Long> refs = new HashSet<>();
            for (FieldDescriptor fd : desc.fields()) {
                if (!fd.isAggregateReference()) continue;
                for (long ref : fd.referencedTypeIds()) {   // union → несколько рёбер
                    if (ref > 0) refs.add(ref);
                }
            }
            edges.put(desc.typeId(), refs);
        }

        // Транзитивное замыкание (BFS из каждой вершины)
        Map<Long, Set<Long>> reachable = new HashMap<>();
        for (Long startId : edges.keySet()) {
            Set<Long> visited = new HashSet<>();
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(startId);
            while (!stack.isEmpty()) {
                long cur = stack.pop();
                if (!visited.add(cur)) continue;
                for (Long next : edges.getOrDefault(cur, Set.of())) {
                    if (!visited.contains(next)) stack.push(next);
                }
            }
            visited.remove(startId);
            reachable.put(startId, Set.copyOf(visited));
        }
        return new AccessFilterGraph(reachable);
    }
}
