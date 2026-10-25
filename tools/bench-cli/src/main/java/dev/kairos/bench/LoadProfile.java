package dev.kairos.bench;

import java.time.Duration;

/** Named benchmark profiles and their exact rate/time contracts. */
enum LoadProfile {
    SMOKE("smoke", Duration.ofMinutes(2), 500, 500),
    STEADY_20K("steady-20k", Duration.ofMinutes(30), 20_000, 20_000),
    RAMP_50K("ramp-50k", Duration.ofMinutes(20), 20_000, 50_000),
    BURST("burst", Duration.ofMinutes(1), 50_000, 50_000);

    private final String cliName;
    private final Duration duration;
    private final int initialRate;
    private final int finalRate;

    LoadProfile(String cliName, Duration duration, int initialRate, int finalRate) {
        this.cliName = cliName;
        this.duration = duration;
        this.initialRate = initialRate;
        this.finalRate = finalRate;
    }

    static LoadProfile parse(String name) {
        for (LoadProfile profile : values()) if (profile.cliName.equals(name)) return profile;
        throw new IllegalArgumentException("Unknown profile: " + name);
    }

    String cliName() {
        return cliName;
    }

    Duration duration() {
        return duration;
    }

    int rateAt(Duration elapsed) {
        if (this != RAMP_50K) return initialRate;
        long rampMillis = Duration.ofMinutes(10).toMillis();
        double fraction = Math.min(1d, Math.max(0d, (double) elapsed.toMillis() / rampMillis));
        return initialRate + (int) Math.round((finalRate - initialRate) * fraction);
    }
}
