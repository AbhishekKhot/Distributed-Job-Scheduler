package dev.kairos.maintenance.partitions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.maintenance.partitions.application.PartitionDay;
import dev.kairos.maintenance.partitions.application.PartitionManager;
import dev.kairos.maintenance.partitions.application.PartitionStore;
import dev.kairos.maintenance.partitions.application.PartitionTable;
import dev.kairos.maintenance.partitions.infrastructure.PartitionCommandLine;
import dev.kairos.maintenance.partitions.infrastructure.PartitionDdl;
import dev.kairos.maintenance.partitions.infrastructure.PartitionProperties;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartitionPlanningTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "audit_log_p20260230",
                "audit_log_p20261301",
                "audit_log_p20260001",
                "audit_log_p20261025;DROP TABLE x",
                "public.audit_log_p20261025",
                "audit_log_default",
                "run_history_p20261025",
                "audit_log_p00001025"
            })
    void catalogNamesCannotSmuggleNonDatesOrDifferentTablesIntoDdl(String name) {
        assertThat(PartitionDay.parse(PartitionTable.AUDIT_LOG, name)).isEmpty();
    }

    @Test
    void leapDaysAndYearBoundariesHaveFixedNamesAndUtcBounds() {
        var leap = new PartitionDay(PartitionTable.RUN_HISTORY, LocalDate.of(2028, 2, 29));
        assertThat(leap.name()).isEqualTo("run_history_p20280229");
        assertThat(PartitionDay.parse(PartitionTable.RUN_HISTORY, leap.name())).contains(leap);
        assertThat(PartitionDdl.attach(leap)).contains("2028-02-29 00:00:00+00", "2028-03-01 00:00:00+00");
        var year = new PartitionDay(PartitionTable.AUDIT_LOG, LocalDate.of(2026, 12, 31));
        assertThat(PartitionDdl.boundCheck(year)).contains("2027-01-01 00:00:00+00");
        assertThatThrownBy(() -> new PartitionDay(PartitionTable.AUDIT_LOG, LocalDate.of(0, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionDay(PartitionTable.AUDIT_LOG, LocalDate.of(9999, 12, 31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "audit_log;DROP TABLE users",
                "audit_log\"",
                "scheduler.audit_log",
                "users",
                "run_history_p20261025--"
            })
    void identifierQuotingRequiresTheFixedAllowList(String name) {
        assertThatThrownBy(() -> PartitionDdl.qualified(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void managerUsesOneUtcDayRegardlessOfClockZone() {
        var seen = new ArrayList<LocalDate>();
        PartitionStore store = day -> {
            seen.add(day);
            return new PartitionStore.Result("control", false, 0, 0, 0, 0);
        };
        var clock = Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneId.of("Asia/Kolkata"));
        assertThat(new PartitionManager(clock, List.of(store, store)).run()).hasSize(2);
        assertThat(seen).containsOnly(LocalDate.of(2026, 12, 31));
        assertThatThrownBy(() -> new PartitionManager(clock, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(PartitionTable.AUDIT_LOG.retentionDays()).isEqualTo(90);
        assertThat(PartitionTable.TASK_OUTPUTS.retentionDays()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "partitions --help", "", "unknown", "partitions --date 2020-01-01"})
    void helpAndInvalidArgumentsNeverOpenDatabases(String arguments) {
        var out = new StringWriter();
        var err = new StringWriter();
        var calls = new AtomicInteger();
        int status = PartitionCommandLine.execute(
                arguments.isEmpty() ? new String[0] : arguments.split(" "),
                () -> {
                    calls.incrementAndGet();
                    return List.of();
                },
                new PrintWriter(out, true),
                new PrintWriter(err, true));
        assertThat(status).isEqualTo(arguments.contains("--help") ? 0 : 2);
        assertThat(calls.get()).isZero();
    }

    @Test
    void cliPrintsSafeSummaryAndReturnsNonzeroWithoutExceptionPayload() {
        var out = new StringWriter();
        var err = new StringWriter();
        int status = PartitionCommandLine.execute(
                new String[] {"partitions"},
                () -> List.of(
                        new PartitionStore.Result("cell-0", false, 8, 2, 3, 4),
                        new PartitionStore.Result("control", true, 0, 0, 0, 0)),
                new PrintWriter(out, true),
                new PrintWriter(err, true));
        assertThat(status).isZero();
        assertThat(out.toString())
                .contains(
                        "cell-0: complete; created=8 moved=2 dropped=3 purged=4",
                        "skipped (another partition manager holds the lock)");
        String privateValue = "sensitive-" + java.util.UUID.randomUUID();
        int failed = PartitionCommandLine.execute(
                new String[] {"partitions"},
                () -> {
                    throw new IllegalStateException(privateValue);
                },
                new PrintWriter(out, true),
                new PrintWriter(err, true));
        assertThat(failed).isEqualTo(1);
        assertThat(err.toString()).contains("IllegalStateException").doesNotContain(privateValue);
    }

    @Test
    void timingAndBatchSettingsAreBounded() {
        assertThat(new PartitionProperties(Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofMinutes(2), 2000)
                        .purgeBatchSize())
                .isEqualTo(2000);
        assertThatThrownBy(() ->
                        new PartitionProperties(Duration.ZERO, Duration.ofSeconds(30), Duration.ofMinutes(2), 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionProperties(
                        Duration.ofSeconds(61), Duration.ofSeconds(90), Duration.ofMinutes(2), 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionProperties(
                        Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMinutes(2), 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionProperties(
                        Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(20), 2000))
                .isInstanceOf(IllegalArgumentException.class);
        for (int size : new int[] {0, 10001})
            assertThatThrownBy(() -> new PartitionProperties(
                            Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofMinutes(2), size))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
