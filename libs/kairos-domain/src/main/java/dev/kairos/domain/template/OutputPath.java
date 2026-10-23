package dev.kairos.domain.template;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Tiny response selector grammar: root, dot properties and non-negative decimal array indexes. */
final class OutputPath {
    private OutputPath() {}

    static List<Step> parse(String path) {
        if (path.isEmpty() || path.length() > 256 || path.charAt(0) != '$') throw OutputSpec.invalid();
        List<Step> steps = new ArrayList<>();
        int i = 1;
        while (i < path.length()) {
            if (steps.size() >= 32) throw OutputSpec.invalid();
            char c = path.charAt(i++);
            if (c == '.') {
                int start = i;
                while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') i++;
                String name = path.substring(start, i);
                if (!name.matches("^[A-Za-z_][A-Za-z0-9_-]{0,63}$")) throw OutputSpec.invalid();
                steps.add(new Property(name));
            } else if (c == '[') {
                int end = path.indexOf(']', i);
                if (end < 0 || end == i || end - i > 9) throw OutputSpec.invalid();
                String index = path.substring(i, end);
                if (!index.chars().allMatch(digit -> digit >= '0' && digit <= '9')) throw OutputSpec.invalid();
                steps.add(new Index(Integer.parseInt(index)));
                i = end + 1;
            } else throw OutputSpec.invalid();
        }
        return List.copyOf(steps);
    }

    static @Nullable JsonValue extract(@Nullable JsonValue body, List<Step> steps) {
        JsonValue current = body == null ? JsonValue.NullValue.INSTANCE : body;
        if (body == null) return null;
        for (Step step : steps) {
            if (step instanceof Property property && current instanceof JsonValue.ObjectValue object) {
                JsonValue next = object.values().get(property.name());
                if (next == null) return null;
                current = next;
            } else if (step instanceof Index index && current instanceof JsonValue.ArrayValue array) {
                if (index.value() >= array.values().size()) return null;
                current = array.values().get(index.value());
            } else return null;
        }
        return current;
    }

    sealed interface Step permits Property, Index {}

    record Property(String name) implements Step {}

    record Index(int value) implements Step {}
}
