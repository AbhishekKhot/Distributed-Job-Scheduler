package dev.kairos.maintenance.partitions.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** A validated UTC day and date-derived identifier; invalid catalog names cannot become DDL targets. */
public record PartitionDay(PartitionTable table, LocalDate date) {
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    public PartitionDay {
        java.util.Objects.requireNonNull(table);
        java.util.Objects.requireNonNull(date);
        if (date.getYear() < 1 || date.getYear() > 9999 || date.plusDays(1).getYear() > 9999)
            throw new IllegalArgumentException("Unsupported partition date");
    }

    public String name() {
        String name = table.table() + "_p" + DAY.format(date);
        if (!Pattern.matches("^(run_history|task_outputs|audit_log)_p[0-9]{8}$", name))
            throw new IllegalArgumentException("Invalid partition identifier");
        return name;
    }

    public static Optional<PartitionDay> parse(PartitionTable table, String name) {
        var matcher = Pattern.compile("^" + table.table() + "_p([0-9]{8})$").matcher(name);
        if (!matcher.matches()) return Optional.empty();
        try {
            return Optional.of(new PartitionDay(table, LocalDate.parse(matcher.group(1), DAY)));
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
