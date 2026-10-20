package dev.kairos.security.password;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The password rules of signup and password change (LLD section 3.3): 12 to 128 characters, not equal to the email,
 * and not in the bundled list of common passwords.
 *
 * <p>Length is counted in Unicode code points, so a password of emoji is not penalised for its UTF-16 length. The
 * email and the common-password list are compared case-insensitively. A violation is reported with a stable code on
 * the request field, never with the submitted value.
 *
 * <p>Immutable and thread-safe.
 */
public final class PasswordPolicy {

    /** The minimum length in code points. */
    public static final int MIN_LENGTH = 12;

    /** The maximum length in code points. */
    public static final int MAX_LENGTH = 128;

    /** The password is shorter than {@value #MIN_LENGTH} characters. */
    public static final String TOO_SHORT = "too-short";

    /** The password is longer than {@value #MAX_LENGTH} characters. */
    public static final String TOO_LONG = "too-long";

    /** The password equals the account's email. */
    public static final String SAME_AS_EMAIL = "same-as-email";

    /** The password is in the list of common passwords. */
    public static final String COMMON = "common-password";

    /** The detail the LLD gives for every password violation. */
    public static final String DETAIL = "Password must be 12 to 128 characters and not a common password.";

    private static final String BUNDLED_LIST = "common-passwords.txt";
    // The bundled list is small; a cap keeps a mistaken replacement from loading an unbounded file into memory.
    private static final int MAX_LIST_ENTRIES = 1_000_000;

    private final Set<String> common;

    /**
     * Creates a policy over a list of common passwords.
     *
     * @param commonPasswords the passwords to reject; compared lower-cased
     */
    public PasswordPolicy(Set<String> commonPasswords) {
        Set<String> lower = new HashSet<>();
        for (String password : commonPasswords) {
            lower.add(password.toLowerCase(Locale.ROOT));
        }
        this.common = Set.copyOf(lower);
    }

    /**
     * Creates a policy over the bundled common-password list.
     *
     * @return the policy
     * @throws UncheckedIOException if the bundled list cannot be read
     */
    public static PasswordPolicy withBundledList() {
        return new PasswordPolicy(loadBundledList());
    }

    /**
     * Returns the first rule the password breaks.
     *
     * @param password the password
     * @param email the account's email, or {@code null} if not known
     * @return the violation code ({@link #TOO_SHORT}, {@link #TOO_LONG}, {@link #SAME_AS_EMAIL} or {@link #COMMON}),
     *     or empty if the password is acceptable
     */
    public Optional<String> violation(CharSequence password, @Nullable String email) {
        String text = password.toString();
        int length = text.codePointCount(0, text.length());
        if (length < MIN_LENGTH) {
            return Optional.of(TOO_SHORT);
        }
        if (length > MAX_LENGTH) {
            return Optional.of(TOO_LONG);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (email != null && lower.equals(email.toLowerCase(Locale.ROOT))) {
            return Optional.of(SAME_AS_EMAIL);
        }
        if (common.contains(lower)) {
            return Optional.of(COMMON);
        }
        return Optional.empty();
    }

    /**
     * Checks a password and throws the LLD's validation problem if it breaks a rule.
     *
     * @param field the request field, for example {@code password} or {@code newPassword}
     * @param password the password
     * @param email the account's email, or {@code null} if not known
     * @throws ValidationFailedException with code {@link ErrorCode#VALIDATION}, detail {@link #DETAIL} and one field
     *     error carrying the violation code
     */
    public void check(String field, CharSequence password, @Nullable String email) {
        Objects.requireNonNull(field, "field");
        Optional<String> violation = violation(password, email);
        if (violation.isPresent()) {
            throw new ValidationFailedException(
                    ErrorCode.VALIDATION, DETAIL, List.of(new FieldError(field, violation.get())));
        }
    }

    /**
     * Returns the number of common passwords this policy rejects.
     *
     * @return the list size
     */
    public int commonPasswordCount() {
        return common.size();
    }

    static Set<String> loadBundledList() {
        InputStream stream = PasswordPolicy.class.getResourceAsStream(BUNDLED_LIST);
        if (stream == null) {
            throw new UncheckedIOException(new IOException("bundled common-password list is missing"));
        }
        Set<String> passwords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && passwords.size() < MAX_LIST_ENTRIES) {
                String entry = line.strip();
                if (!entry.isEmpty() && !entry.startsWith("#")) {
                    passwords.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the bundled common-password list", e);
        }
        return passwords;
    }
}
