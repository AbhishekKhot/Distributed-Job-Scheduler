package dev.kairos.mocktarget.behavior.application;

import java.util.Map;
import java.util.Set;

/** Immutable bounded behavior, resolved from a named profile plus explicit per-request overrides. */
public record Behavior(int status, int latencyMs, double failRate, Integer bodyBytes, String profile) {
    private static final Set<String> FIELDS = Set.of("profile", "status", "latencyMs", "failRate", "bodyBytes");

    public static Behavior resolve(String pathProfile, Map<String, String[]> query) {
        if (!FIELDS.containsAll(query.keySet())
                || query.values().stream().anyMatch(values -> values.length != 1 || values[0].length() > 32))
            throw new IllegalArgumentException("Invalid behavior parameters.");
        String selected = query.containsKey("profile")
                ? query.get("profile")[0]
                : (pathProfile == null ? "healthy" : pathProfile);
        if (pathProfile != null && query.containsKey("profile"))
            throw new IllegalArgumentException("Profile specified twice.");
        Behavior base = switch (selected) {
            case "healthy" -> new Behavior(200, 0, 0, null, selected);
            case "retryable" -> new Behavior(503, 0, 0, null, selected);
            case "client-error" -> new Behavior(400, 0, 0, null, selected);
            case "slow" -> new Behavior(200, 250, 0, null, selected);
            case "mixed" -> new Behavior(200, 20, 0.1, null, selected);
            default -> throw new IllegalArgumentException("Unknown behavior profile.");
        };
        int status = integer(query, "status", base.status), latency = integer(query, "latencyMs", base.latencyMs);
        double failure = query.containsKey("failRate") ? Double.parseDouble(query.get("failRate")[0]) : base.failRate;
        Integer body =
                query.containsKey("bodyBytes") ? Integer.valueOf(integer(query, "bodyBytes", 0)) : base.bodyBytes;
        if (status < 200
                || status > 599
                || latency < 0
                || latency > 60000
                || !Double.isFinite(failure)
                || failure < 0
                || failure > 1
                || (body != null && (body < 0 || body > 1_048_576)))
            throw new IllegalArgumentException("Behavior parameter out of range.");
        return new Behavior(status, latency, failure, body, selected);
    }

    /** Decode only the URL query; form bodies remain untouched for signature verification. */
    public static Map<String, String[]> query(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        if (raw.length() > 4096) throw new IllegalArgumentException("Query too long.");
        Map<String, String[]> result = new java.util.HashMap<>();
        for (String field : raw.split("&", -1)) {
            int equals = field.indexOf('=');
            if (equals < 1) throw new IllegalArgumentException("Invalid query.");
            String key =
                    java.net.URLDecoder.decode(field.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8);
            String value =
                    java.net.URLDecoder.decode(field.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8);
            if (!FIELDS.contains(key) || result.putIfAbsent(key, new String[] {value}) != null)
                throw new IllegalArgumentException("Invalid query.");
        }
        return result;
    }

    private static int integer(Map<String, String[]> query, String key, int fallback) {
        return query.containsKey(key) ? Integer.parseInt(query.get(key)[0]) : fallback;
    }
}
