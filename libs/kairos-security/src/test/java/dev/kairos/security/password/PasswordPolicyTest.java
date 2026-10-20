package dev.kairos.security.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = PasswordPolicy.withBundledList();

    @Test
    void acceptsALongUncommonPassword() {
        assertThat(policy.violation("violet-harbour-71-quiet", "asha@example.com"))
                .isEmpty();
        assertThatCode(() -> policy.check("password", "violet-harbour-71-quiet", null))
                .doesNotThrowAnyException();
    }

    @Test
    void enforcesTheLengthInCodePoints() {
        assertThat(policy.violation("short-pw-11", null)).contains(PasswordPolicy.TOO_SHORT);
        assertThat(policy.violation("x".repeat(129), null)).contains(PasswordPolicy.TOO_LONG);
        assertThat(policy.violation("y".repeat(128), null)).isEmpty();
        // Twelve emoji are twelve characters, though 24 UTF-16 units.
        assertThat(policy.violation("🔒".repeat(12), null)).isEmpty();
        assertThat(policy.violation("🔒".repeat(11), null)).contains(PasswordPolicy.TOO_SHORT);
    }

    @Test
    void rejectsTheEmailIgnoringCase() {
        assertThat(policy.violation("Asha@Example.com", "asha@example.com")).contains(PasswordPolicy.SAME_AS_EMAIL);
    }

    @Test
    void rejectsBundledCommonPasswordsIgnoringCase() {
        assertThat(policy.commonPasswordCount()).isGreaterThan(100);
        assertThat(policy.violation("Password1234", null)).contains(PasswordPolicy.COMMON);
        assertThat(policy.violation("QWERTY123456", null)).contains(PasswordPolicy.COMMON);
        assertThat(new PasswordPolicy(Set.of("Custom-Blocked-1")).violation("custom-blocked-1", null))
                .contains(PasswordPolicy.COMMON);
    }

    @Test
    void reportsTheLldProblemWithoutTheValue() {
        ValidationFailedException failure = catchThrowableOfType(
                ValidationFailedException.class, () -> policy.check("newPassword", "too-short", null));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION);
        assertThat(failure.detail()).isEqualTo(PasswordPolicy.DETAIL);
        assertThat(failure.fieldErrors()).containsExactly(new FieldError("newPassword", PasswordPolicy.TOO_SHORT));
        assertThat(failure.getMessage()).doesNotContain("too-short-");
    }
}
