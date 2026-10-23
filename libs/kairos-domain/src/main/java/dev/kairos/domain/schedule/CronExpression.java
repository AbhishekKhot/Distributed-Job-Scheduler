package dev.kairos.domain.schedule;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import dev.kairos.domain.error.ValidationFailedException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A validated 5-field Unix cron expression ({@code minute hour day-of-month month day-of-week}).
 *
 * <p>Parsing uses cron-utils with its {@code UNIX} definition: ranges, lists, steps and month or
 * day names are allowed, seconds, years and {@code @} macros are not. When both day-of-month and
 * day-of-week are restricted, a day matches if either matches, as in Unix cron. An expression
 * must fire at least once in the 5 years after 2000-01-01, so expressions such as
 * {@code 0 0 31 2 *} that never fire are rejected.
 *
 * <p>The expression is matched against local wall-clock times only; mapping a local time to an
 * instant (and the daylight-saving rules that involves) is done by the schedule calculator. The
 * text is kept in a canonical form: fields separated by single spaces.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class CronExpression {

    /** Longest accepted expression text, checked before parsing. */
    public static final int MAX_LENGTH = 128;

    /** Field error code for an expression that is malformed or never fires (LLD section 3.6). */
    public static final String INVALID_CRON = "invalid-cron";

    private static final int FIELDS = 5;

    private static final CronDefinition UNIX = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);

    private static final LocalDateTime PROBE_START = LocalDateTime.of(2000, 1, 1, 0, 0);

    private static final LocalDateTime PROBE_END = PROBE_START.plusYears(5);

    private final String text;

    private final ExecutionTime executionTime;

    private CronExpression(String text, ExecutionTime executionTime) {
        this.text = text;
        this.executionTime = executionTime;
    }

    /**
     * Parses and validates an expression.
     *
     * @param field the field path reported on failure, for example {@code schedule.expression}
     * @param text the expression
     * @return the expression
     * @throws ValidationFailedException with code {@link #INVALID_CRON} on {@code field} if the
     *     text is too long, does not have exactly 5 fields, does not parse, or never fires
     */
    public static CronExpression parse(String field, String text) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(text, "text");
        String canonical = canonicalize(text);
        if (canonical == null) {
            throw ValidationFailedException.of(field, INVALID_CRON);
        }
        ExecutionTime executionTime;
        try {
            Cron cron = new CronParser(UNIX).parse(canonical).validate();
            executionTime = ExecutionTime.forCron(cron);
        } catch (RuntimeException e) {
            // cron-utils reports malformed input with several unchecked exception types.
            throw ValidationFailedException.of(field, INVALID_CRON);
        }
        CronExpression expression = new CronExpression(canonical, executionTime);
        Optional<LocalDateTime> first = expression.nextMatchAfter(PROBE_START.minusMinutes(1));
        if (first.isEmpty() || first.get().isAfter(PROBE_END)) {
            throw ValidationFailedException.of(field, INVALID_CRON);
        }
        return expression;
    }

    /**
     * Parses an expression reported on field {@code schedule.expression}.
     *
     * @param text the expression
     * @return the expression
     * @throws ValidationFailedException as {@link #parse(String, String)}
     */
    public static CronExpression parse(String text) {
        return parse("schedule.expression", text);
    }

    /**
     * Splits the text into fields with a hand-written scanner (no regular expression) and joins
     * them with single spaces.
     *
     * @return the canonical text, or null if the text is too long, has a character outside the
     *     cron alphabet, or does not have exactly 5 fields
     */
    private static @Nullable String canonicalize(String text) {
        if (text.length() > MAX_LENGTH) {
            return null;
        }
        List<String> fields = new ArrayList<>(FIELDS);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!current.isEmpty()) {
                    fields.add(current.toString());
                    current.setLength(0);
                }
            } else if (isCronChar(c)) {
                current.append(c);
            } else {
                return null;
            }
        }
        if (!current.isEmpty()) {
            fields.add(current.toString());
        }
        return fields.size() == FIELDS ? String.join(" ", fields) : null;
    }

    private static boolean isCronChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || c == '*'
                || c == '/'
                || c == ','
                || c == '-';
    }

    /**
     * Returns the canonical text.
     *
     * @return the 5 fields separated by single spaces
     */
    public String text() {
        return text;
    }

    /**
     * Returns the first local time strictly after {@code after} that matches the expression.
     *
     * @param after the exclusive lower bound; seconds and smaller units are ignored
     * @return the next matching local time (whole minutes), or empty if there is none
     */
    public Optional<LocalDateTime> nextMatchAfter(LocalDateTime after) {
        Objects.requireNonNull(after, "after");
        // Wall-clock arithmetic in UTC, which has no transitions, so every local time exists once.
        ZonedDateTime from = after.truncatedTo(ChronoUnit.MINUTES).atZone(ZoneOffset.UTC);
        return executionTime.nextExecution(from).map(ZonedDateTime::toLocalDateTime);
    }

    /**
     * Tells whether a local time matches the expression.
     *
     * @param local the local time; it matches only on a whole minute
     * @return true if the expression fires at that local time
     */
    public boolean matches(LocalDateTime local) {
        Objects.requireNonNull(local, "local");
        return local.getSecond() == 0 && local.getNano() == 0 && executionTime.isMatch(local.atZone(ZoneOffset.UTC));
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof CronExpression that && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    /**
     * Returns the canonical text.
     *
     * @return the same value as {@link #text()}
     */
    @Override
    public String toString() {
        return text;
    }
}
