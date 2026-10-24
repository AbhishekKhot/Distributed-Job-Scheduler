package dev.kairos.executor.consume.infrastructure;

import dev.kairos.messaging.client.PartitionFlowControl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;

/** Binds executor consumer capacity, per-tier in-flight, poll-batch and lag metrics. */
public final class ExecutorConsumerMetrics {

    private final MeterRegistry meters;
    private final Map<DispatchTier, AtomicLong> lags = new EnumMap<>(DispatchTier.class);
    private final Map<DispatchTier, DistributionSummary> pollSizes = new EnumMap<>(DispatchTier.class);
    private final Counter invalidDispatches;

    /** Creates the metrics binding. */
    public ExecutorConsumerMetrics(MeterRegistry meters, DispatchCapacity capacity) {
        this.meters = Objects.requireNonNull(meters, "meters");
        Objects.requireNonNull(capacity, "capacity");
        invalidDispatches = Counter.builder("kairos.executor.dispatch.invalid")
                .description("Dispatch records rejected before processing because required identifiers are malformed")
                .tag("reason", "identifier_or_shard")
                .register(meters);
        Gauge.builder("kairos.executor.capacity", capacity, DispatchCapacity::limit)
                .description("Maximum dispatch records in flight per executor pod")
                .register(meters);
        Gauge.builder("kairos.executor.inflight.total", capacity, DispatchCapacity::inFlight)
                .description("Reserved and active dispatch records per executor pod")
                .register(meters);
        for (DispatchTier tier : DispatchTier.values()) {
            AtomicLong lag = new AtomicLong();
            lags.put(tier, lag);
            Gauge.builder("kairos.executor.consume.lag", lag, AtomicLong::get)
                    .description("Maximum Kafka partition lag for this priority tier")
                    .tag("priority", tier.label())
                    .register(meters);
            pollSizes.put(
                    tier,
                    DistributionSummary.builder("kairos.executor.records.per.poll")
                            .description("Dispatch records returned by a consumer poll")
                            .tag("priority", tier.label())
                            .register(meters));
        }
    }

    /** Binds a tier's in-flight gauge. */
    public void bind(DispatchTier tier, PartitionFlowControl flow) {
        Gauge.builder("kairos.executor.inflight", flow, PartitionFlowControl::inFlight)
                .description("Dispatch records being processed by priority tier")
                .tag("priority", tier.label())
                .register(meters);
    }

    /** Records one poll's batch size. */
    public void poll(DispatchTier tier, int records) {
        pollSizes.get(tier).record(records);
    }

    /** Counts a valid-wire dispatch whose identifiers cannot be routed safely. */
    public void invalidDispatch() {
        invalidDispatches.increment();
    }

    /** Samples Kafka's bounded records-lag-max metric. */
    public void lag(DispatchTier tier, Map<MetricName, ? extends Metric> metrics) {
        double max = metrics.entrySet().stream()
                .filter(entry -> entry.getKey().name().equals("records-lag-max"))
                .mapToDouble(entry -> ((Number) entry.getValue().metricValue()).doubleValue())
                .max()
                .orElse(0d);
        lags.get(tier).set((long) max);
    }
}
