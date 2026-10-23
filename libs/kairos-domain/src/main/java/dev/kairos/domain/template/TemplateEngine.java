package dev.kairos.domain.template;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.TaskKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Context-aware substitution with no expression language, recursive expansion, reflection or raw JSON insertion. */
public final class TemplateEngine {
    private static final int MAX_RENDERED_TEXT = 65536;

    private TemplateEngine() {}

    /** Where a value will be inserted. */
    public enum Destination {
        /** An encoded path segment. */
        PATH,
        /** An encoded query value. */
        QUERY,
        /** A header value with control characters forbidden. */
        HEADER,
        /** JSON body values or string interpolation. */
        BODY,
        /** Fixed URL authority: placeholders are forbidden. */
        HOST,
        /** Fixed URL protocol: placeholders are forbidden. */
        SCHEME
    }

    /**
     * Checks references against the consumer's declared ancestors and output metadata.
     * @param template the parsed field
     * @param destination the insertion site
     * @param consumer the task being validated
     * @param ancestors its transitive ancestors
     * @param declarations all resolved output declarations
     */
    public static void validate(
            Template template,
            Destination destination,
            TaskKey consumer,
            Set<TaskKey> ancestors,
            Map<TaskKey, Map<String, OutputSpec>> declarations) {
        if ((destination == Destination.HOST || destination == Destination.SCHEME)
                && !template.references().isEmpty()) throw Template.invalid();
        for (Template.Reference reference : template.references()) {
            if (!(reference instanceof Template.OutputReference output)) continue;
            Map<String, OutputSpec> declared = declarations.get(output.task());
            OutputSpec spec = declared == null ? null : declared.get(output.output());
            if (!ancestors.contains(output.task()) || spec == null) {
                throw new ValidationFailedException(
                        ErrorCode.INVALID_PLACEHOLDER,
                        consumer.value() + " references tasks." + output.task().value() + ".outputs." + output.output()
                                + ", which is not an upstream output.",
                        List.of());
            }
            if (spec.sensitive() && (destination == Destination.PATH || destination == Destination.QUERY))
                throw Template.invalid();
        }
    }

    /**
     * Validates every request value and rejects placeholders in JSON property names.
     * @param request the request overrides
     * @param consumer the task
     * @param ancestors its transitive ancestors
     * @param declarations all resolved endpoint outputs
     */
    public static void validateRequest(
            RequestTemplate request,
            TaskKey consumer,
            Set<TaskKey> ancestors,
            Map<TaskKey, Map<String, OutputSpec>> declarations) {
        request.pathParams()
                .values()
                .forEach(text -> validate(Template.compile(text), Destination.PATH, consumer, ancestors, declarations));
        request.query()
                .values()
                .forEach(
                        text -> validate(Template.compile(text), Destination.QUERY, consumer, ancestors, declarations));
        request.headers()
                .values()
                .forEach(text ->
                        validate(Template.compile(text), Destination.HEADER, consumer, ancestors, declarations));
        JsonValue body = request.body();
        if (body != null) validateBody(body, consumer, ancestors, declarations);
    }

    private static void validateBody(
            JsonValue value,
            TaskKey consumer,
            Set<TaskKey> ancestors,
            Map<TaskKey, Map<String, OutputSpec>> declarations) {
        switch (value) {
            case JsonValue.TextValue text ->
                validate(Template.compile(text.value()), Destination.BODY, consumer, ancestors, declarations);
            case JsonValue.ArrayValue array ->
                array.values().forEach(child -> validateBody(child, consumer, ancestors, declarations));
            case JsonValue.ObjectValue object ->
                object.values().forEach((name, child) -> {
                    if (name.contains("{{") || name.contains("}}")) throw Template.invalid();
                    validateBody(child, consumer, ancestors, declarations);
                });
            default -> {}
        }
    }

    /**
     * Renders scalar text once. PATH and QUERY results are already percent-encoded and must not be encoded again.
     * @param template the parsed template
     * @param destination the insertion site
     * @param context run id and extracted upstream values
     * @return text plus sensitivity provenance
     */
    public static RenderedText renderText(Template template, Destination destination, Context context) {
        if ((destination == Destination.HOST || destination == Destination.SCHEME)
                && !template.references().isEmpty()) throw failed();
        StringBuilder value = new StringBuilder();
        boolean sensitive = false;
        for (Template.Part part : template.parts()) {
            if (part instanceof Template.Literal literal) value.append(literal.text());
            else if (part instanceof Template.ReferencePart reference) {
                OutputValue resolved = resolve(reference.reference(), context);
                if (resolved.sensitive() && destination != Destination.HEADER && destination != Destination.BODY)
                    throw failed();
                sensitive |= resolved.sensitive();
                if (resolved.value() instanceof JsonValue.TextValue text) value.append(text.value());
                else if (resolved.value() instanceof JsonValue.ArrayValue
                        || resolved.value() instanceof JsonValue.ObjectValue) throw failed();
                else value.append(resolved.value().json());
            }
            if (value.length() > MAX_RENDERED_TEXT) throw failed();
        }
        String raw = value.toString();
        if (destination == Destination.PATH) checkPath(raw);
        if (destination == Destination.HEADER || destination == Destination.HOST || destination == Destination.SCHEME) {
            if (raw.chars().anyMatch(c -> c < 32 || c == 127)) throw failed();
        }
        String rendered = destination == Destination.PATH || destination == Destination.QUERY ? encode(raw) : raw;
        if (rendered.getBytes(StandardCharsets.UTF_8).length > MAX_RENDERED_TEXT) throw failed();
        return new RenderedText(rendered, sensitive);
    }

    /**
     * Renders JSON values: a lone placeholder retains its JSON type; mixed text becomes an escaped JSON string.
     * @param body the input JSON tree
     * @param context upstream values
     * @return immutable JSON and sensitivity provenance
     */
    public static RenderedJson renderJson(JsonValue body, Context context) {
        try {
            // Bounds even directly constructed trees before recursive traversal.
            body.json();
            RenderedJson rendered = renderBody(body, context);
            rendered.value().json();
            return rendered;
        } catch (ValidationFailedException failure) {
            throw failed();
        }
    }

    private static RenderedJson renderBody(JsonValue value, Context context) {
        return switch (value) {
            case JsonValue.TextValue text -> {
                Template template = Template.compile(text.value());
                if (template.isSingleReference()) {
                    OutputValue output = resolve(template.references().getFirst(), context);
                    yield new RenderedJson(output.value(), output.sensitive());
                }
                RenderedText rendered = renderText(template, Destination.BODY, context);
                yield new RenderedJson(new JsonValue.TextValue(rendered.value()), rendered.sensitive());
            }
            case JsonValue.ArrayValue array -> {
                List<JsonValue> values = new ArrayList<>();
                boolean sensitive = false;
                for (JsonValue child : array.values()) {
                    RenderedJson rendered = renderBody(child, context);
                    values.add(rendered.value());
                    sensitive |= rendered.sensitive();
                }
                yield new RenderedJson(new JsonValue.ArrayValue(values), sensitive);
            }
            case JsonValue.ObjectValue object -> {
                Map<String, JsonValue> values = new LinkedHashMap<>();
                boolean sensitive = false;
                for (var entry : object.values().entrySet()) {
                    if (entry.getKey().contains("{{") || entry.getKey().contains("}}")) throw failed();
                    RenderedJson rendered = renderBody(entry.getValue(), context);
                    values.put(entry.getKey(), rendered.value());
                    sensitive |= rendered.sensitive();
                }
                yield new RenderedJson(new JsonValue.ObjectValue(values), sensitive);
            }
            default -> new RenderedJson(value, false);
        };
    }

    /**
     * Renders a complete override. Sensitive results belong in memory at send time, never cleartext Kafka payloads.
     * @param request validated request templates
     * @param context run and upstream values
     * @return encoded URL components, raw checked header values, and typed JSON
     */
    public static RenderedRequest renderRequest(RequestTemplate request, Context context) {
        Map<String, String> path = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        boolean sensitive = renderMap(request.pathParams(), path, Destination.PATH, context);
        sensitive |= renderMap(request.query(), query, Destination.QUERY, context);
        sensitive |= renderMap(request.headers(), headers, Destination.HEADER, context);
        JsonValue body = request.body();
        RenderedJson rendered = body == null ? null : renderJson(body, context);
        return new RenderedRequest(
                path,
                query,
                headers,
                rendered == null ? null : rendered.value(),
                sensitive || (rendered != null && rendered.sensitive()));
    }

    private static boolean renderMap(
            Map<String, String> source, Map<String, String> target, Destination destination, Context context) {
        boolean sensitive = false;
        for (var entry : source.entrySet()) {
            RenderedText rendered = renderText(Template.compile(entry.getValue()), destination, context);
            target.put(entry.getKey(), rendered.value());
            sensitive |= rendered.sensitive();
        }
        return sensitive;
    }

    private static OutputValue resolve(Template.Reference reference, Context context) {
        if (reference instanceof Template.RunReference)
            return new OutputValue(new JsonValue.TextValue(context.runId().toString()), false);
        Template.OutputReference output = (Template.OutputReference) reference;
        Map<String, OutputValue> task = context.outputs().get(output.task());
        OutputValue value = task == null ? null : task.get(output.output());
        if (value == null) throw failed();
        return value;
    }

    private static void checkPath(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (raw.indexOf('\\') >= 0
                || lower.contains("%2e")
                || lower.contains("%2f")
                || lower.contains("%5c")
                || lower.contains("%25")) throw failed();
        for (String segment : raw.split("/", -1)) if (segment.equals(".") || segment.equals("..")) throw failed();
    }

    private static String encode(String raw) {
        StringBuilder encoded = new StringBuilder();
        for (byte octet : raw.getBytes(StandardCharsets.UTF_8)) {
            int c = octet & 255;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '~') encoded.append((char) c);
            else
                encoded.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return encoded.toString();
    }

    private static ValidationFailedException failed() {
        return new ValidationFailedException(
                ErrorCode.RENDER_FAILED, "The request values could not be rendered safely.", List.of());
    }

    /**
     * Immutable runtime inputs. Keep sensitive values decrypted only for the target call.
     * @param runId current run id
     * @param outputs producer task to named extracted values
     */
    public record Context(UUID runId, Map<TaskKey, Map<String, OutputValue>> outputs) {
        /** Copies producer maps and enforces the per-task output cap. */
        public Context {
            Objects.requireNonNull(runId, "runId");
            if (outputs.size() > 200) throw failed();
            Map<TaskKey, Map<String, OutputValue>> copied = new LinkedHashMap<>();
            outputs.forEach((task, values) -> {
                if (values.size() > 256) throw failed();
                int bytes = 2;
                for (var entry : values.entrySet()) {
                    bytes += OutputExtractor.serializedSize(
                                    entry.getKey(), entry.getValue().value())
                            + 1;
                    if (bytes - 1 > OutputExtractor.MAX_OUTPUT_BYTES) throw failed();
                }
                copied.put(task, Map.copyOf(values));
            });
            outputs = Map.copyOf(copied);
        }

        @Override
        public String toString() {
            return "TemplateContext[<redacted>]";
        }
    }

    /**
     * Rendered scalar text with sensitivity provenance.
     * @param value rendered text
     * @param sensitive whether any substitution carried sensitive data
     */
    public record RenderedText(String value, boolean sensitive) {
        @Override
        public String toString() {
            return "RenderedText[<redacted>]";
        }
    }
    /**
     * Rendered JSON with sensitivity provenance.
     * @param value rendered typed JSON
     * @param sensitive whether any substitution carried sensitive data
     */
    public record RenderedJson(JsonValue value, boolean sensitive) {
        @Override
        public String toString() {
            return "RenderedJson[<redacted>]";
        }
    }
    /**
     * URL values are already encoded; adapters must preserve them without a second encoding pass.
     * @param pathParams encoded path segments
     * @param query encoded query values
     * @param headers checked raw headers
     * @param body typed JSON, or null for no body
     * @param sensitive whether the request contains sensitive upstream data
     */
    public record RenderedRequest(
            Map<String, String> pathParams,
            Map<String, String> query,
            Map<String, String> headers,
            @Nullable JsonValue body,
            boolean sensitive) {
        /** Copies all maps. */
        public RenderedRequest {
            pathParams = Map.copyOf(pathParams);
            query = Map.copyOf(query);
            headers = Map.copyOf(headers);
        }

        @Override
        public String toString() {
            return "RenderedRequest[<redacted>]";
        }
    }
}
