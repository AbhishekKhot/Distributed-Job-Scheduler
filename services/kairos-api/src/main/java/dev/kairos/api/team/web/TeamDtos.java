package dev.kairos.api.team.web;

import dev.kairos.api.team.application.CreatedTeam;
import dev.kairos.api.team.application.Member;
import dev.kairos.api.team.application.TeamDetails;
import dev.kairos.api.team.application.TeamQuotas;
import dev.kairos.api.team.application.TeamUsage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies of the team APIs (LLD section 3.4). */
public final class TeamDtos {

    /** The longest email accepted; matches {@code users.email}. */
    static final int MAX_EMAIL = 254;

    private static final String REDACTED = "<redacted>";

    private TeamDtos() {}

    /**
     * Body of {@code POST /v1/teams}. The name rule is checked by the use case, which reports {@code pattern}.
     *
     * @param name the team name, {@code ^[a-z][a-z0-9-]{2,62}$}
     */
    @Schema(name = "CreateTeamRequest")
    public record CreateTeamRequest(
            @Schema(example = "payments", pattern = "^[a-z][a-z0-9-]{2,62}$", minLength = 3, maxLength = 63) @NotNull String name) {}

    /**
     * Body of {@code POST /v1/team/members}.
     *
     * @param email the email of an existing user without a team
     */
    @Schema(name = "AddMemberRequest")
    public record AddMemberRequest(
            @Schema(example = "ravi@example.com", maxLength = MAX_EMAIL) @NotBlank @Size(max = MAX_EMAIL) String email) {

        /** Leaves the email out. */
        @Override
        public String toString() {
            return "AddMemberRequest[email=" + REDACTED + "]";
        }
    }

    /**
     * A team's quotas.
     *
     * @param maxJobs the most jobs
     * @param maxExecPerSec the most executions per second
     * @param maxInflight the most runs executing at once
     * @param maxWorkflowTasks the most tasks in one workflow
     * @param backfillPerSec the backfill pace, runs per second
     * @param redrivePerSec the DLQ redrive pace, runs per second
     */
    @Schema(name = "TeamQuotas")
    public record QuotasResponse(
            @Schema(example = "10000") int maxJobs,
            @Schema(example = "2000") int maxExecPerSec,
            @Schema(example = "2000") int maxInflight,
            @Schema(example = "200") int maxWorkflowTasks,
            @Schema(example = "100") int backfillPerSec,
            @Schema(example = "100") int redrivePerSec) {

        static QuotasResponse of(TeamQuotas quotas) {
            return new QuotasResponse(
                    quotas.maxJobs(),
                    quotas.maxExecPerSec(),
                    quotas.maxInflight(),
                    quotas.maxWorkflowTasks(),
                    quotas.backfillPerSec(),
                    quotas.redrivePerSec());
        }
    }

    /**
     * What a team uses now.
     *
     * @param jobs jobs that are not deleted
     * @param execPerSec executions per second over the last complete minute
     * @param inflight runs executing now
     */
    @Schema(name = "TeamUsage")
    public record UsageResponse(
            @Schema(example = "1240") long jobs,
            @Schema(example = "310") long execPerSec,
            @Schema(example = "42") long inflight) {

        static UsageResponse of(TeamUsage usage) {
            return new UsageResponse(usage.jobs(), usage.execPerSec(), usage.inflight());
        }
    }

    /**
     * Response of {@code POST /v1/teams}.
     *
     * @param teamId the new team's id
     * @param name the team name
     * @param quotas the team's quotas
     * @param signingKey the request-signing key, shown only in this response
     * @param createdAt when the team was created
     */
    @Schema(name = "CreateTeamResponse")
    public record CreateTeamResponse(
            @Schema(example = "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f")
            UUID teamId,

            @Schema(example = "payments") String name,
            QuotasResponse quotas,

            @Schema(
                    description = "Key that targets use to verify X-Kairos-Signature; shown only once.",
                    example = "ksig_v1_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
            String signingKey,

            @Schema(example = "2026-10-14T09:30:00Z") Instant createdAt) {

        static CreateTeamResponse of(CreatedTeam created) {
            return new CreateTeamResponse(
                    created.team().id().value(),
                    created.team().name(),
                    QuotasResponse.of(created.team().quotas()),
                    created.signingKey().reveal(),
                    created.team().createdAt());
        }

        /** Leaves the signing key out. */
        @Override
        public String toString() {
            return "CreateTeamResponse[teamId=" + teamId + ", name=" + name + ", signingKey=" + REDACTED + "]";
        }
    }

    /**
     * Response of {@code GET /v1/team}.
     *
     * @param teamId the team's id
     * @param name the team name
     * @param quotas the team's quotas
     * @param usage what the team uses now
     * @param createdAt when the team was created
     */
    @Schema(name = "TeamResponse")
    public record TeamResponse(
            @Schema(example = "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f")
            UUID teamId,

            @Schema(example = "payments") String name,
            QuotasResponse quotas,
            UsageResponse usage,
            @Schema(example = "2026-10-14T09:30:00Z") Instant createdAt) {

        static TeamResponse of(TeamDetails details) {
            return new TeamResponse(
                    details.team().id().value(),
                    details.team().name(),
                    QuotasResponse.of(details.team().quotas()),
                    UsageResponse.of(details.usage()),
                    details.team().createdAt());
        }
    }

    /**
     * A team member.
     *
     * @param userId the user's id
     * @param email the user's email
     * @param addedAt when the user was added
     */
    @Schema(name = "MemberResponse")
    public record MemberResponse(
            @Schema(example = "0192e7c3-6b5a-7e9d-8c1f-2a3b4c5d6e7f")
            UUID userId,

            @Schema(example = "ravi@example.com") String email,
            @Schema(example = "2026-10-14T09:30:00Z") Instant addedAt) {

        static MemberResponse of(Member member) {
            return new MemberResponse(member.user().value(), member.email(), member.addedAt());
        }

        /** Leaves the email out. */
        @Override
        public String toString() {
            return "MemberResponse[userId=" + userId + ", email=" + REDACTED + ", addedAt=" + addedAt + "]";
        }
    }
}
