package dev.kairos.domain.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KairosExceptionTest {

    @Test
    void notFoundDefaultsToTheGenericCode() {
        NotFoundException e = new NotFoundException("The job does not exist.");

        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.detail()).isEqualTo("The job does not exist.");
        assertThat(new NotFoundException(ErrorCode.USER_NOT_FOUND, "No user has this email.").errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void eachSubclassAcceptsOnlyCodesWithItsStatus() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NotFoundException(ErrorCode.JOB_NAME_TAKEN, "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ConflictException(ErrorCode.NOT_FOUND, "x"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PreconditionFailedException(ErrorCode.VALIDATION, "x"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ValidationFailedException(ErrorCode.INVALID_STATE, "x", List.of()));
    }

    @Test
    void conflictAndQuotaCarryTheirCodes() {
        assertThat(new ConflictException(ErrorCode.JOB_NAME_TAKEN, "A job with this name already exists in the team.")
                        .errorCode())
                .isEqualTo(ErrorCode.JOB_NAME_TAKEN);
        assertThat(new QuotaExceededException("The team has reached its job limit.").errorCode())
                .isEqualTo(ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    void preconditionFailuresCoverStaleAndMissingVersions() {
        assertThat(PreconditionFailedException.versionMismatch().errorCode()).isEqualTo(ErrorCode.VERSION_MISMATCH);
        assertThat(new PreconditionFailedException(
                                ErrorCode.IF_MATCH_REQUIRED, "Send the current version in the If-Match header.")
                        .errorCode())
                .isEqualTo(ErrorCode.IF_MATCH_REQUIRED);
    }

    @Test
    void validationErrorsAreCopiedAndUnmodifiable() {
        List<FieldError> errors = new ArrayList<>(List.of(new FieldError("name", FieldError.PATTERN)));
        ValidationFailedException e = new ValidationFailedException(errors);
        errors.clear();

        assertThat(e.fieldErrors()).containsExactly(new FieldError("name", FieldError.PATTERN));
        assertThat(e.detail()).isEqualTo(ValidationFailedException.DEFAULT_DETAIL);
        assertThat(new ValidationFailedException(ErrorCode.CYCLE_DETECTED, "The workflow has a cycle.", List.of())
                        .fieldErrors())
                .isEmpty();
    }

    @Test
    void fieldErrorRequiresBothParts() {
        assertThatNullPointerException().isThrownBy(() -> new FieldError(null, FieldError.REQUIRED));
        assertThatNullPointerException().isThrownBy(() -> new FieldError("name", null));
    }
}
