package dev.kairos.domain.template;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.TaskKey;
import java.util.ArrayList;
import java.util.List;

/** Bounded hand-written tokenizer. Only run.id and tasks.task.outputs.name expressions are recognized. */
public final class Template {
    /** Maximum source characters per field. */
    public static final int MAX_CHARACTERS = 16384;

    private final List<Part> parts;
    private final List<Reference> references;

    private Template(List<Part> parts) {
        this.parts = List.copyOf(parts);
        List<Reference> found = new ArrayList<>();
        for (Part part : parts) {
            if (part instanceof ReferencePart reference) found.add(reference.reference());
        }
        this.references = List.copyOf(found);
    }

    /**
     * Tokenizes without evaluation or reflection; rejects unclosed, nested and unsupported expressions.
     * @param source the field template
     * @return the immutable tokens
     */
    public static Template compile(String source) {
        if (source.length() > MAX_CHARACTERS) throw invalid();
        JsonValues.checkText(source);
        List<Part> parts = new ArrayList<>();
        int literal = 0;
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("}}", i)) throw invalid();
            if (!source.startsWith("{{", i)) {
                i++;
                continue;
            }
            if (literal < i) parts.add(new Literal(source.substring(literal, i)));
            int end = source.indexOf("}}", i + 2);
            if (end < 0) throw invalid();
            String expression = source.substring(i + 2, end).strip();
            if (expression.equals("run.id")) parts.add(new ReferencePart(new RunReference()));
            else {
                String[] fields = expression.split("\\.", -1);
                if (fields.length != 4
                        || !fields[0].equals("tasks")
                        || !fields[2].equals("outputs")
                        || !name(fields[1])
                        || !name(fields[3])) throw invalid();
                parts.add(new ReferencePart(new OutputReference(new TaskKey(fields[1]), fields[3])));
            }
            i = end + 2;
            literal = i;
        }
        if (literal < source.length()) parts.add(new Literal(source.substring(literal)));
        return new Template(parts);
    }

    /** Returns immutable references in source order, including repeated occurrences.
     * @return immutable references in source order, including repeated occurrences
     */
    public List<Reference> references() {
        return references;
    }

    List<Part> parts() {
        return parts;
    }

    boolean isSingleReference() {
        return parts.size() == 1 && parts.getFirst() instanceof ReferencePart;
    }

    static boolean name(String text) {
        return text.length() <= 64 && text.matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    }

    static ValidationFailedException invalid() {
        return new ValidationFailedException(
                ErrorCode.INVALID_PLACEHOLDER, "The placeholder syntax or destination is not allowed.", List.of());
    }

    /** A parsed placeholder, never an expression to evaluate. */
    public sealed interface Reference permits RunReference, OutputReference {}
    /** The current run id. */
    public record RunReference() implements Reference {}
    /**
     * A reference to one declared task output.
     * @param task the producer task
     * @param output its declared output name
     */
    public record OutputReference(TaskKey task, String output) implements Reference {
        /** Checks the output identifier. */
        public OutputReference {
            if (!name(output)) throw invalid();
        }
    }

    sealed interface Part permits Literal, ReferencePart {}

    record Literal(String text) implements Part {}

    record ReferencePart(Reference reference) implements Part {}

    @Override
    public String toString() {
        return "Template[references=" + references.size() + "]";
    }
}
