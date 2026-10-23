package dev.kairos.api.team.web;

import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.security.UserTokenOnly;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.api.support.problem.Problem;
import dev.kairos.api.team.application.AddMemberUseCase;
import dev.kairos.api.team.application.CreateTeamUseCase;
import dev.kairos.api.team.application.MemberKey;
import dev.kairos.api.team.application.MemberPage;
import dev.kairos.api.team.application.RemoveMemberUseCase;
import dev.kairos.api.team.application.TeamQueryUseCase;
import dev.kairos.api.team.web.TeamDtos.AddMemberRequest;
import dev.kairos.api.team.web.TeamDtos.CreateTeamRequest;
import dev.kairos.api.team.web.TeamDtos.CreateTeamResponse;
import dev.kairos.api.team.web.TeamDtos.MemberResponse;
import dev.kairos.api.team.web.TeamDtos.TeamResponse;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's team and its members (LLD section 3.4, APIs 8 to 12). The team always comes from the credential.
 * Creating a team is open to a user without one; reading works with a user token or an API key; adding and removing
 * members need a user token. The controller only maps HTTP to the use cases and back.
 */
@RestController
@Tag(name = "Team", description = "Create the caller's team, read it, and manage its members.")
public class TeamController {

    /** The list name bound into member-list cursors. */
    static final String MEMBERS_LIST = "team-members";

    private final CreateTeamUseCase creates;
    private final TeamQueryUseCase queries;
    private final AddMemberUseCase additions;
    private final RemoveMemberUseCase removals;
    private final CursorCodec cursors;

    /**
     * Creates the controller.
     *
     * @param creates API 8
     * @param queries APIs 9 and 10
     * @param additions API 11
     * @param removals API 12
     * @param cursors encodes and checks list cursors
     */
    public TeamController(
            CreateTeamUseCase creates,
            TeamQueryUseCase queries,
            AddMemberUseCase additions,
            RemoveMemberUseCase removals,
            CursorCodec cursors) {
        this.creates = Objects.requireNonNull(creates, "creates");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.additions = Objects.requireNonNull(additions, "additions");
        this.removals = Objects.requireNonNull(removals, "removals");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /**
     * API 8: creates a team for a user without one.
     *
     * @param principal the caller
     * @param body the team name
     * @return 201 with the team and its signing key
     */
    @PostMapping(
            path = "/v1/teams",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Create a team",
            description = "Creates a team for a user who has none, with the default quotas, and makes the caller its"
                    + " first member. The team's encryption and signing keysets are created with it; signingKey (used"
                    + " by targets to verify X-Kairos-Signature) is shown only in this response. Call refresh to get"
                    + " a token that carries the team. Rate-limited per user.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = CreateTeamRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "create",
                                                            value = TeamExamples.CREATE_TEAM_REQUEST))))
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            headers = @Header(name = "Location", description = "/v1/team", schema = @Schema(type = "string")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreateTeamResponse.class),
                            examples = @ExampleObject(name = "created", value = TeamExamples.CREATE_TEAM_RESPONSE)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "validation", value = TeamExamples.TEAM_VALIDATION)))
    @ApiResponse(
            responseCode = "409",
            description = "Already in a team, or the name is taken",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = {
                                @ExampleObject(name = "already-in-team", value = TeamExamples.ALREADY_IN_TEAM),
                                @ExampleObject(name = "team-name-taken", value = TeamExamples.TEAM_NAME_TAKEN)
                            }))
    @ApiResponse(
            responseCode = "429",
            description = "Too many requests",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "rate-limited", value = TeamExamples.TEAM_RATE_LIMITED)))
    public ResponseEntity<CreateTeamResponse> create(
            KairosPrincipal principal, @Valid @RequestBody CreateTeamRequest body) {
        CreateTeamResponse created = CreateTeamResponse.of(creates.create(principal, body.name()));
        return ResponseEntity.created(URI.create("/v1/team")).body(created);
    }

    /**
     * API 9: the caller's team.
     *
     * @param team the caller's team
     * @return 200 with the team, its quotas and its usage
     */
    @GetMapping(path = "/v1/team", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get my team",
            description = "Returns the caller's team with its quotas and current usage: jobs that are not deleted,"
                    + " executions per second over the last complete minute, and runs executing now.")
    @ApiResponse(
            responseCode = "200",
            description = "The team",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TeamResponse.class),
                            examples = @ExampleObject(name = "team", value = TeamExamples.TEAM_RESPONSE)))
    public TeamResponse team(TeamContext team) {
        return TeamResponse.of(queries.team(team.teamId()));
    }

    /**
     * API 10: the team's members, newest first.
     *
     * @param team the caller's team
     * @param limit the page size, 1 to 200 (default 50)
     * @param cursor the {@code nextCursor} of the previous page
     * @return 200 with one page of members
     */
    @GetMapping(path = "/v1/team/members", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List members", description = "Lists the members of the caller's team, newest first.")
    @ApiResponse(
            responseCode = "200",
            description = "One page of members",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(name = "members", value = TeamExamples.MEMBERS_RESPONSE)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid limit or cursor",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "validation", value = TeamExamples.MEMBERS_VALIDATION)))
    public Page<MemberResponse> members(
            TeamContext team,
            @Parameter(description = "Page size, 1 to 200 (default 50).")
                    @RequestParam(name = "limit", required = false)
                    @Nullable Integer limit,
            @Parameter(description = "Opaque value from nextCursor.")
                    @RequestParam(name = "cursor", required = false)
                    @Nullable String cursor) {
        int size = PageLimit.resolve(limit);
        MemberKey after = cursor == null ? null : decode(team.teamId(), cursor);
        MemberPage page = queries.members(team.teamId(), after, size);
        MemberKey next = page.next();
        return new Page<>(
                page.members().stream().map(MemberResponse::of).toList(),
                next == null ? null : encode(team.teamId(), next));
    }

    /**
     * API 11: adds an existing user without a team.
     *
     * @param team the caller's team
     * @param user the calling user
     * @param body the user's email
     * @return 201 with the member
     */
    @PostMapping(
            path = "/v1/team/members",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @UserTokenOnly
    @Operation(
            summary = "Add a member",
            description = "Adds an existing user, found by email, who has no team. No email is sent; the user gets"
                    + " the team on their next token refresh. Adding a user who is already a member returns the"
                    + " member unchanged. Needs a user access token; rate-limited per user.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = AddMemberRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "add",
                                                            value = TeamExamples.ADD_MEMBER_REQUEST))))
    @ApiResponse(
            responseCode = "201",
            description = "Added",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MemberResponse.class),
                            examples = @ExampleObject(name = "added", value = TeamExamples.ADD_MEMBER_RESPONSE)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "validation", value = TeamExamples.ADD_MEMBER_VALIDATION)))
    @ApiResponse(
            responseCode = "404",
            description = "No account has the email",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "user-not-found", value = TeamExamples.USER_NOT_FOUND)))
    @ApiResponse(
            responseCode = "409",
            description = "The user belongs to another team",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples =
                                    @ExampleObject(
                                            name = "user-in-another-team",
                                            value = TeamExamples.USER_IN_ANOTHER_TEAM)))
    @ApiResponse(
            responseCode = "429",
            description = "Too many requests",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "rate-limited", value = TeamExamples.MEMBER_RATE_LIMITED)))
    public ResponseEntity<MemberResponse> addMember(
            TeamContext team, UserPrincipal user, @Valid @RequestBody AddMemberRequest body) {
        MemberResponse member = MemberResponse.of(additions.add(team.teamId(), user, body.email()));
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    /**
     * API 12: removes a member, or leaves with the caller's own id.
     *
     * @param team the caller's team
     * @param user the calling user
     * @param userId the member's user id
     * @return 204
     */
    @DeleteMapping(path = "/v1/team/members/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @UserTokenOnly
    @Operation(
            summary = "Remove a member or leave",
            description = "Removes a member, or leaves the team with the caller's own id. The user's team is"
                    + " cleared and all their sessions are revoked; their access tokens stop working within 30s. A"
                    + " team must keep at least one member. Needs a user access token.")
    @ApiResponse(responseCode = "204", description = "Removed")
    @ApiResponse(
            responseCode = "404",
            description = "Not a member",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "not-found", value = TeamExamples.MEMBER_NOT_FOUND)))
    @ApiResponse(
            responseCode = "409",
            description = "Last member",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "last-member", value = TeamExamples.LAST_MEMBER)))
    public ResponseEntity<Void> removeMember(
            TeamContext team,
            UserPrincipal user,
            @Parameter(description = "The member's user id; the caller's own id to leave.") @PathVariable("userId")
                    UUID userId) {
        removals.remove(team.teamId(), user, memberId(userId));
        return ResponseEntity.noContent().build();
    }

    // A well-formed UUID that is not a Kairos id (UUIDv7) cannot be a member.
    private static UserId memberId(UUID userId) {
        try {
            return new UserId(userId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(RemoveMemberUseCase.NOT_FOUND_DETAIL);
        }
    }

    private String encode(TeamId team, MemberKey key) {
        return cursors.encode(
                MEMBERS_LIST,
                team,
                List.of(key.createdAt().toString(), key.user().value().toString()));
    }

    private MemberKey decode(TeamId team, String cursor) {
        List<String> values = cursors.decode(MEMBERS_LIST, team, cursor);
        if (values.size() != 2) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
        try {
            return new MemberKey(Instant.parse(values.get(0)), new UserId(UUID.fromString(values.get(1))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
    }
}
