package dev.kairos.domain.schedule;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/** IANA region zone ids, the only zones a cron schedule may use. */
final class IanaZones {

    /** Field path of the time zone. */
    static final String FIELD = "schedule.timezone";

    /** Longest accepted zone id, checked before lookup. */
    static final int MAX_LENGTH = 64;

    private static final Set<String> AVAILABLE = Set.copyOf(ZoneId.getAvailableZoneIds());

    private IanaZones() {}

    /**
     * Tells whether a zone id is a region id known to the JDK's time-zone database.
     *
     * @return true for ids such as {@code Europe/London} or {@code UTC}; false for fixed offsets
     *     ({@code +05:30}, {@code UTC+01:00}) and unknown ids
     */
    static boolean isIanaZone(String id) {
        return id.length() <= MAX_LENGTH && AVAILABLE.contains(id);
    }

    /**
     * Parses a region zone id.
     *
     * @throws ValidationFailedException with code {@link FieldError#FORMAT} on {@link #FIELD} if
     *     the id is not a region id
     */
    static ZoneId parse(String timezone) {
        Objects.requireNonNull(timezone, "timezone");
        if (!isIanaZone(timezone)) {
            throw ValidationFailedException.of(FIELD, FieldError.FORMAT);
        }
        return ZoneId.of(timezone);
    }
}
