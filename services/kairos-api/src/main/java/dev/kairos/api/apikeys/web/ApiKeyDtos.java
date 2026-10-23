package dev.kairos.api.apikeys.web;

import dev.kairos.api.apikeys.application.ApiKeyStore;
import dev.kairos.api.apikeys.application.ApiKeysUseCase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Request and response bodies for LLD APIs 13–15. */
public final class ApiKeyDtos {
    private ApiKeyDtos() {}

    @Schema(name = "CreateApiKeyRequest")
    public record CreateRequest(
            @Schema(example = "ci-pipeline", minLength = 1, maxLength = 64) @NotNull @Size(min = 1, max = 64) String name,

            @Schema(
                    description = "Optional expiry, strictly in the future.",
                    example = "2027-04-14T00:00:00Z",
                    nullable = true)
            @Nullable Instant expiresAt) {}

    @Schema(name = "CreatedApiKey")
    public record CreatedResponse(
            @Schema(example = "7Hq2Lm9Xc4Vb8Nz1") String keyId,
            @Schema(example = "ci-pipeline") String name,

            @Schema(description = "Full API key; returned only at creation.")
            String key,

            @Nullable Instant expiresAt,
            Instant createdAt) {
        static CreatedResponse of(ApiKeysUseCase.Created created) {
            return new CreatedResponse(
                    created.keyId(), created.name(), created.key().reveal(), created.expiresAt(), created.createdAt());
        }

        @Override
        public String toString() {
            return "CreatedResponse[keyId=" + keyId + ", key=<redacted>]";
        }
    }

    @Schema(name = "ApiKeyMetadata")
    public record MetadataResponse(
            String keyId,
            String name,
            UUID createdBy,
            @Nullable Instant expiresAt,
            @Nullable Instant lastUsedAt,
            @Nullable Instant revokedAt) {
        static MetadataResponse of(ApiKeyStore.Metadata metadata) {
            return new MetadataResponse(
                    metadata.keyId(),
                    metadata.name(),
                    metadata.createdBy().value(),
                    metadata.expiresAt(),
                    metadata.lastUsedAt(),
                    metadata.revokedAt());
        }
    }
}
