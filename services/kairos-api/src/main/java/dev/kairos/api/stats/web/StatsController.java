package dev.kairos.api.stats.web;

import dev.kairos.api.security.TeamContext;
import dev.kairos.api.stats.application.StatsQueries;
import dev.kairos.api.stats.application.TeamStats;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Team-scoped aggregate statistics (LLD API 68). */
@RestController
@Tag(name = "Stats", description = "Aggregate execution statistics for the caller's team.")
public class StatsController {
    private static final Pattern WINDOW = Pattern.compile("^([1-9][0-9]*)(h|d)$");
    private final StatsQueries stats;

    /** Creates the controller. */
    public StatsController(StatsQueries stats) {
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    /** LLD API 68. */
    @GetMapping(path = "/v1/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get team statistics",
            description = "Returns execution counts, latency percentiles and DLQ size.")
    public TeamStats get(
            TeamContext team, @RequestParam(name = "window", required = false) @Nullable String requested) {
        String window = requested == null ? "24h" : requested;
        Matcher matcher = WINDOW.matcher(window);
        if (!matcher.matches()) throw ValidationFailedException.of("window", FieldError.RANGE);
        try {
            long value = Long.parseLong(matcher.group(1));
            long hours = "d".equals(matcher.group(2)) ? Math.multiplyExact(value, 24) : value;
            if (hours < 1 || hours > 168) throw ValidationFailedException.of("window", FieldError.RANGE);
            return stats.get(team.teamId(), window, Duration.ofHours(hours));
        } catch (NumberFormatException | ArithmeticException e) {
            throw ValidationFailedException.of("window", FieldError.RANGE);
        }
    }
}
