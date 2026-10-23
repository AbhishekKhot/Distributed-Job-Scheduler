package dev.kairos.api.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.accounts.application.AccountRateLimits;
import dev.kairos.api.security.ApiKeyPrincipal;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The team rules without a database: names, quotas, usage, paging and the create race checks. */
class TeamRulesTest {

    private static final TeamQuotas DEFAULTS = new TeamQuotas(10_000, 2000, 2000, 200, 100, 100);

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-14T09:30:00.123456789Z"));

    @Test
    void namesFollowTheLldRule() {
        assertThat(TeamNames.isValid("payments")).isTrue();
        assertThat(TeamNames.isValid("a-1")).isTrue();
        assertThat(TeamNames.isValid("a" + "b".repeat(62))).isTrue();
        for (String bad : List.of("ab", "a" + "b".repeat(63), "Payments", "1abc", "-abc", "ab_c", "ab c", "abé")) {
            assertThat(TeamNames.isValid(bad)).as(bad).isFalse();
        }
        assertThatThrownBy(() -> TeamNames.validate("x")).isInstanceOfSatisfying(ValidationFailedException.class, e -> {
            assertThat(e.detail()).isEqualTo("Team name must match ^[a-z][a-z0-9-]{2,62}$.");
            assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                assertThat(error.field()).isEqualTo("name");
                assertThat(error.code()).isEqualTo("pattern");
            });
        });
        assertThat(TeamNames.validate("payments")).isEqualTo("payments");
    }

    @Test
    void storedQuotasFallBackToTheDefaultsKeyByKey() {
        TeamQuotas quotas = TeamQuotas.withDefaults(
                Map.of("maxJobs", 5L, "maxInflight", 0L, "redrivePerSec", (long) Integer.MAX_VALUE + 1), DEFAULTS);

        assertThat(quotas).isEqualTo(new TeamQuotas(5, 2000, 2000, 200, 100, 100));
        assertThat(DEFAULTS.asMap().keySet())
                .containsExactly(
                        "maxJobs",
                        "maxExecPerSec",
                        "maxInflight",
                        "maxWorkflowTasks",
                        "backfillPerSec",
                        "redrivePerSec");
        assertThatThrownBy(() -> new TeamQuotas(0, 1, 1, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usageAddsUpAndIsNeverNegative() {
        assertThat(new TeamUsage(1, 2, 3).plus(new TeamUsage(4, 5, 6))).isEqualTo(new TeamUsage(5, 7, 9));
        assertThatThrownBy(() -> new TeamUsage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMemberListReadsOneExtraRowToKnowWhetherAnotherPageExists() {
        FakeStore store = new FakeStore();
        TeamId team = TestIds.teamId();
        for (int i = 0; i < 3; i++) {
            store.members.add(new Member(
                    TestIds.userId(), "u" + i + "@example.com", clock.instant().minusSeconds(i)));
        }
        TeamQueryUseCase queries = new TeamQueryUseCase(store, (t, now) -> new TeamUsage(0, 0, 0), DEFAULTS, clock);

        MemberPage first = queries.members(team, null, 2);
        assertThat(first.members()).hasSize(2);
        assertThat(first.next()).isEqualTo(store.members.get(1).key());
        assertThat(store.lastLimit).isEqualTo(3);

        MemberPage all = queries.members(team, null, 3);
        assertThat(all.members()).hasSize(3);
        assertThat(all.next()).isNull();
        assertThatThrownBy(() -> queries.team(team))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NO_TEAM));
    }

    @Test
    void createRefusesApiKeysAndRechecksTheTeamUnderTheLock() {
        FakeStore store = new FakeStore();
        UserId user = TestIds.userId();
        CreateTeamUseCase creates = createUseCase(store);
        UserPrincipal caller = new UserPrincipal(user, null, UUID.randomUUID());

        assertThatThrownBy(() -> creates.create(new ApiKeyPrincipal("k".repeat(16), TestIds.teamId()), "payments"))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ALREADY_IN_TEAM));
        assertThatThrownBy(() -> creates.create(caller, "payments"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));

        // Another request gives the user a team between the first read and the locked one.
        store.membership = new TeamStore.Membership(null);
        store.lockedMembership = new TeamStore.Membership(TestIds.teamId());
        assertThatThrownBy(() -> creates.create(caller, "payments"))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ALREADY_IN_TEAM));
        assertThat(store.teams).isEmpty();

        store.lockedMembership = new TeamStore.Membership(null);
        CreatedTeam created = creates.create(caller, "payments");
        assertThat(created.team().createdAt()).isEqualTo(Instant.parse("2026-10-14T09:30:00.123456Z"));
        assertThat(created.team().quotas()).isEqualTo(DEFAULTS);
        assertThat(created.toString()).doesNotContain(created.signingKey().reveal());
        assertThat(store.keysets).containsOnlyKeys(KeyPurpose.SECRETS, KeyPurpose.OUTPUTS, KeyPurpose.SIGNING);
        assertThat(store.joined).containsExactly(user);
    }

    private CreateTeamUseCase createUseCase(FakeStore store) {
        KeysetService keysets = new KeysetService(
                LocalKeyEncryptionKey.forLocalProfile(HexFormat.of().formatHex(new byte[32]), true),
                new SecureRandom(),
                Duration.ofMinutes(1),
                10);
        AccountRateLimits limits = new AccountRateLimits((limit, subject) -> {});
        return new CreateTeamUseCase(
                store,
                keysets,
                entry -> {},
                new DirectTransactions(),
                limits,
                new KairosIdFactory(clock, new SecureRandom()),
                DEFAULTS,
                clock);
    }

    private static final class DirectTransactions implements TeamTransactions {

        @Override
        public <T extends @Nullable Object> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }

    private static final class FakeStore implements TeamStore {

        private TeamStore.@Nullable Membership membership;
        private TeamStore.@Nullable Membership lockedMembership;
        private final List<Member> members = new ArrayList<>();
        private final List<NewTeam> teams = new ArrayList<>();
        private final List<UserId> joined = new ArrayList<>();
        private final Map<KeyPurpose, SealedKeyset> keysets = new HashMap<>();
        private int lastLimit;

        @Override
        public Optional<Membership> lockUser(UserId user) {
            return Optional.ofNullable(lockedMembership);
        }

        @Override
        public Optional<Membership> findMembership(UserId user) {
            return Optional.ofNullable(membership);
        }

        @Override
        public boolean insertTeam(NewTeam team) {
            teams.add(team);
            return true;
        }

        @Override
        public void insertKeyset(TeamId team, KeyPurpose purpose, SealedKeyset keyset, Instant now) {
            keysets.put(purpose, keyset);
        }

        @Override
        public void join(UserId user, TeamId team, Instant now) {
            joined.add(user);
        }

        @Override
        public Optional<StoredTeam> findTeam(TeamId team) {
            return Optional.empty();
        }

        @Override
        public List<Member> members(TeamId team, @Nullable MemberKey after, int limit) {
            lastLimit = limit;
            return members.subList(0, Math.min(limit, members.size()));
        }

        @Override
        public AddOutcome addByEmail(TeamId team, String email, Instant now) {
            return new AddOutcome.UnknownUser();
        }

        @Override
        public List<UserId> lockMembers(TeamId team) {
            return List.of();
        }

        @Override
        public void leave(UserId user, TeamId team, Instant now) {}
    }
}
