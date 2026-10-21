package dev.kairos.api.accounts.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The user store of the accounts feature ({@code users} in the control database). Other features (team membership)
 * keep their own narrow ports on the same table.
 */
public interface UserAccounts {

    /**
     * Creates a user unless the email is already registered.
     *
     * @param user the new user
     * @return whether the user was created; {@code false} if the email is taken
     */
    boolean insert(NewUser user);

    /**
     * Finds a user by normalised email.
     *
     * @param email the lower-case email
     * @return the user, or empty if no account has the email
     */
    Optional<StoredUser> findByEmail(String email);

    /**
     * Finds a user by id.
     *
     * @param user the user id
     * @return the user, or empty if it does not exist
     */
    Optional<StoredUser> findById(UserId user);

    /**
     * Reads a user's profile with the name of the team.
     *
     * @param user the user id
     * @return the profile, or empty if the user does not exist
     */
    Optional<UserProfile> findProfile(UserId user);

    /**
     * Counts a failed login: adds one to {@code failed_logins} and returns the new count. Runs inside the caller's
     * transaction when there is one, and locks the row until it ends.
     *
     * @param user the user
     * @param now the time of the attempt
     * @return the consecutive failures, including this one
     */
    int incrementFailedLogins(UserId user, Instant now);

    /**
     * Locks the account until a time.
     *
     * @param user the user
     * @param lockedUntil the end of the lock
     * @param now the current time
     */
    void lock(UserId user, Instant lockedUntil, Instant now);

    /**
     * Records a successful login: resets the failure count and the lock and stores the login time, and replaces the
     * password hash when the hashing parameters were raised.
     *
     * @param user the user
     * @param now the login time
     * @param newPasswordHash a rehash of the password, or {@code null} to keep the stored one
     */
    void recordLogin(UserId user, Instant now, @Nullable String newPasswordHash);

    /**
     * Replaces the password hash.
     *
     * @param user the user
     * @param passwordHash the new Argon2id PHC string
     * @param now the time of the change
     */
    void updatePassword(UserId user, String passwordHash, Instant now);

    /**
     * A user to create. The team is always empty at signup.
     *
     * @param id the new user's id
     * @param email the normalised email
     * @param passwordHash the Argon2id PHC string
     * @param createdAt the creation time
     */
    record NewUser(UserId id, String email, String passwordHash, Instant createdAt) {

        /** Validates the components. */
        public NewUser {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(passwordHash, "passwordHash");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        /** Leaves the password hash out. */
        @Override
        public String toString() {
            return "NewUser[id=" + id + "]";
        }
    }

    /**
     * A stored user with what login needs.
     *
     * @param id the user id
     * @param team the user's team, or {@code null}
     * @param email the normalised email
     * @param passwordHash the Argon2id PHC string
     * @param active whether the status is {@code ACTIVE}
     * @param failedLogins the consecutive failed logins
     * @param lockedUntil the end of the current lock, or {@code null}
     */
    record StoredUser(
            UserId id,
            @Nullable TeamId team,
            String email,
            String passwordHash,
            boolean active,
            int failedLogins,
            @Nullable Instant lockedUntil) {

        /** Validates the components. */
        public StoredUser {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(passwordHash, "passwordHash");
        }

        /**
         * Tells whether the account is locked at a time.
         *
         * @param now the time
         * @return whether a lock is in force
         */
        public boolean lockedAt(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        /** Leaves the email and the password hash out. */
        @Override
        public String toString() {
            return "StoredUser[id=" + id + ", team=" + team + ", active=" + active + ", failedLogins=" + failedLogins
                    + ", lockedUntil=" + lockedUntil + "]";
        }
    }
}
