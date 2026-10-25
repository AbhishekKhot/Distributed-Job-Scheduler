package dev.kairos.maintenance.commands.infrastructure;

import dev.kairos.persistence.config.PersistenceProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Control-only DML credentials; operator commands do not require cell connections or KMS. */
@Validated
@ConfigurationProperties("kairos.maintenance.commands")
public record OperatorProperties(@NotNull @Valid PersistenceProperties.Connection database) {}
