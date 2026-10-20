package dev.kairos.persistence.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;

class SqlErrorTranslatorTest {

    private static final ConstraintConflicts JOB_NAMES =
            ConstraintConflicts.of("jobs_team_name_uk", ErrorCode.JOB_NAME_TAKEN, "A job with this name exists.");

    @Test
    void uniqueViolationOnRegisteredConstraintBecomesItsConflict() {
        DuplicateKeyException failure =
                new DuplicateKeyException("insert failed", postgresError("23505", "jobs_team_name_uk"));

        RuntimeException translated = SqlErrorTranslator.translate(failure, JOB_NAMES);

        assertThat(translated).isInstanceOf(ConflictException.class).hasCause(failure);
        ConflictException conflict = (ConflictException) translated;
        assertThat(conflict.errorCode()).isEqualTo(ErrorCode.JOB_NAME_TAKEN);
        assertThat(conflict.detail()).isEqualTo("A job with this name exists.");
    }

    @Test
    void uniqueViolationOnOtherConstraintBecomesGenericConflict() {
        DuplicateKeyException failure = new DuplicateKeyException("insert failed", postgresError("23505", "jobs_pk"));

        RuntimeException translated = SqlErrorTranslator.translate(failure, JOB_NAMES);

        assertThat(translated).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) translated).errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(translated.getMessage()).isEqualTo(ConstraintConflicts.DEFAULT_DETAIL);
    }

    @Test
    void uniqueViolationWithoutConstraintNameBecomesGenericConflict() {
        RuntimeException failure = new DuplicateKeyException("insert failed", new SQLException("dup", "23505"));

        RuntimeException translated = SqlErrorTranslator.translate(failure, JOB_NAMES);

        assertThat(((ConflictException) translated).errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void serializationFailureIsRetryable() {
        RuntimeException failure =
                new PessimisticLockingFailureException("conflict", new SQLException("serialize", "40001"));

        RuntimeException translated = SqlErrorTranslator.translate(failure, ConstraintConflicts.none());

        assertThat(translated).isInstanceOf(RetryableDatabaseException.class).hasCause(failure);
        assertThat(((RetryableDatabaseException) translated).reason())
                .isEqualTo(RetryableDatabaseException.Reason.SERIALIZATION_FAILURE);
    }

    @Test
    void deadlockIsRetryable() {
        RuntimeException failure =
                new PessimisticLockingFailureException("deadlock", new SQLException("deadlock", "40P01"));

        RuntimeException translated = SqlErrorTranslator.translate(failure, ConstraintConflicts.none());

        assertThat(((RetryableDatabaseException) translated).reason())
                .isEqualTo(RetryableDatabaseException.Reason.DEADLOCK);
        assertThat(translated.getMessage()).doesNotContain("deadlock detected");
    }

    @Test
    void otherFailuresAreReturnedUnchanged() {
        RuntimeException checkViolation =
                new DataIntegrityViolationException("check", new SQLException("check", "23514"));
        RuntimeException noSql = new IllegalStateException("not a database failure");
        RuntimeException noState = new DataIntegrityViolationException("x", new SQLException("no state"));

        assertThat(SqlErrorTranslator.translate(checkViolation, JOB_NAMES)).isSameAs(checkViolation);
        assertThat(SqlErrorTranslator.translate(noSql, JOB_NAMES)).isSameAs(noSql);
        assertThat(SqlErrorTranslator.translate(noState, JOB_NAMES)).isSameAs(noState);
    }

    @Test
    void alreadyTranslatedFailuresAreNotTranslatedAgain() {
        RuntimeException conflict = SqlErrorTranslator.translate(
                new DuplicateKeyException("dup", postgresError("23505", "jobs_team_name_uk")), JOB_NAMES);
        RuntimeException retryable = new RetryableDatabaseException(
                RetryableDatabaseException.Reason.DEADLOCK, new SQLException("deadlock", "40P01"));

        assertThat(SqlErrorTranslator.translate(conflict, ConstraintConflicts.none()))
                .isSameAs(conflict);
        assertThat(SqlErrorTranslator.translate(retryable, JOB_NAMES)).isSameAs(retryable);
    }

    @Test
    void sqlStateIsReadFromTheCauseChain() {
        assertThat(SqlErrorTranslator.sqlState(new IllegalStateException(new SQLException("x", "40001"))))
                .isEqualTo("40001");
        assertThat(SqlErrorTranslator.sqlState(new IllegalStateException("no cause")))
                .isNull();
    }

    @Test
    void translateRejectsNulls() {
        assertThatThrownBy(() -> SqlErrorTranslator.translate(new IllegalStateException(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PSQLException postgresError(String sqlState, String constraint) {
        // The server sends fields as <type char><value>\0; C is the SQLSTATE, n the constraint, M the message.
        return new PSQLException(new ServerErrorMessage(
                "SERROR\0C" + sqlState + "\0Mduplicate key value violates unique constraint\0n" + constraint + "\0"));
    }
}
