package dev.kairos.domain.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lookup from database code to constant for one {@link CodedEnum}.
 *
 * <p>Building the index fails if two constants share a code or a code is below 1, so a mistake in
 * an enum fails at class initialisation rather than when a row is read. Immutable and
 * thread-safe.
 *
 * @param <E> the enum type
 */
public final class CodeIndex<E extends Enum<E> & CodedEnum> {

    private final String typeName;
    private final Map<Integer, E> byCode;

    private CodeIndex(String typeName, Map<Integer, E> byCode) {
        this.typeName = typeName;
        this.byCode = byCode;
    }

    /**
     * Indexes the constants of an enum.
     *
     * @param constants every constant, usually {@code values()}
     * @param <E> the enum type
     * @return the index
     * @throws IllegalArgumentException if no constants are given
     * @throws IllegalStateException if a code is below 1 or used twice
     */
    public static <E extends Enum<E> & CodedEnum> CodeIndex<E> of(E[] constants) {
        List<E> list = List.of(constants);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("An enum needs at least one constant");
        }
        String typeName = list.getFirst().getDeclaringClass().getSimpleName();
        for (E constant : list) {
            if (constant.code() < 1) {
                throw new IllegalStateException(typeName + " codes start at 1");
            }
        }
        Map<Integer, E> byCode = list.stream()
                .collect(Collectors.toUnmodifiableMap(CodedEnum::code, Function.identity(), (first, second) -> {
                    throw new IllegalStateException(typeName + " uses code " + first.code() + " twice");
                }));
        return new CodeIndex<>(typeName, byCode);
    }

    /**
     * Returns the constant for a code.
     *
     * @param code the database code
     * @return the constant
     * @throws IllegalArgumentException if no constant has this code
     */
    public E fromCode(int code) {
        E constant = byCode.get(code);
        if (constant == null) {
            throw new IllegalArgumentException("Unknown " + typeName + " code " + code);
        }
        return constant;
    }
}
