package dev.kairos.migrations.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the migrations job, bound from the {@code KAIROS_*} environment variables of the deployment.
 *
 * <p>The property names follow the variables the job is deployed with: {@code KAIROS_CONTROL_DB_URL} binds to
 * {@code kairos.control.db.url}, {@code KAIROS_CELL_<n>_DB_URL} to {@code kairos.cell[n].db.url}, and
 * {@code KAIROS_DB_USERNAME} / {@code KAIROS_DB_PASSWORD} to {@code kairos.db.*}. Cells are numbered from 0 without
 * gaps; cell {@code n} owns an equal, contiguous range of the 256 shards.
 *
 * @param db credentials of the schema owner, used for every database
 * @param control the control database
 * @param cell the cell databases, in cell order
 * @param migrations job settings
 */
@Validated
@ConfigurationProperties("kairos")
public record MigrationProperties(
        @NotNull @Valid Credentials db,
        @NotNull @Valid Database control,
        @NotEmpty @Size(max = 64) List<@NotNull @Valid Database> cell,
        @NotNull @Valid @DefaultValue Migrations migrations) {

    /**
     * Credentials of the role that owns schema {@code scheduler}.
     *
     * @param username the role name
     * @param password the password; never logged
     */
    public record Credentials(
            @NotBlank @Size(max = 63) @Pattern(regexp = "^[a-z_][a-z0-9_]*$") String username,

            @NotBlank @Size(max = 1024) String password) {

        /** Leaves the password out, so the credentials can never end up in a log line. */
        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=<redacted>]";
        }
    }

    /**
     * One database to migrate.
     *
     * @param db its connection settings
     */
    public record Database(@NotNull @Valid Connection db) {}

    /**
     * Connection settings of one database.
     *
     * @param url a PostgreSQL JDBC URL; credentials never go in it
     */
    public record Connection(
            @NotBlank @Size(max = 512) @Pattern(regexp = "^jdbc:postgresql://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[A-Za-z0-9_]+$")
            String url) {}

    /**
     * Settings of the job itself.
     *
     * @param appRole the role the services connect as; it must have DML on every table
     * @param partitionDaysAhead how many days after today get a daily partition
     */
    public record Migrations(
            @NotBlank @Size(max = 63) @Pattern(regexp = "^[a-z_][a-z0-9_]*$") @DefaultValue("sched_app")
            String appRole,

            @Min(0) @Max(30) @DefaultValue("3") int partitionDaysAhead) {}
}
