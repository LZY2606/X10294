package a429.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provenance graph: raw sources -> fields -> engineering values -> aggregates, and time chain. */
public final class DependencyGraph {
    public record Edge(String from, String to, String kind) {
    }

    private final Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<Edge>> incoming = new LinkedHashMap<>();
    private final Set<String> nodes = new LinkedHashSet<>();

    public void node(String node) {
        nodes.add(node);
    }

    public void edge(String from, String to, String kind) {
        node(from);
        node(to);
        outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(from, to, kind));
        incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(new Edge(from, to, kind));
    }

    /** All nodes reachable downstream of {@code startNodes} via provenance edges. */
    public Set<String> downstream(Set<String> startNodes) {
        Set<String> seen = new LinkedHashSet<>(startNodes);
        List<String> queue = new ArrayList<>(startNodes);
        while (!queue.isEmpty()) {
            String node = queue.remove(0);
            for (Edge edge : outgoing.getOrDefault(node, List.of())) {
                if (seen.add(edge.to())) {
                    queue.add(edge.to());
                }
            }
        }
        return seen;
    }

    public Set<String> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        List<Edge> all = new ArrayList<>();
        for (List<Edge> list : outgoing.values()) {
            all.addAll(list);
        }
        return all;
    }
}
