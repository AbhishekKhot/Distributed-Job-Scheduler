package dev.kairos.persistence.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ErrorCode;
import org.junit.jupiter.api.Test;

class ConstraintConflictsTest {

    @Test
    void mapsEachRegisteredConstraintToItsConflict() {
        ConstraintConflicts conflicts = ConstraintConflicts.of(
                        "jobs_team_name_uk", ErrorCode.JOB_NAME_TAKEN, "A job with this name exists.")
                .and("workflows_team_name_uk", ErrorCode.WORKFLOW_NAME_TAKEN, "A workflow with this name exists.");

        assertThat(conflicts.conflictFor("jobs_team_name_uk").errorCode()).isEqualTo(ErrorCode.JOB_NAME_TAKEN);
        assertThat(conflicts.conflictFor("workflows_team_name_uk").errorCode())
                .isEqualTo(ErrorCode.WORKFLOW_NAME_TAKEN);
        assertThat(conflicts.toException("workflows_team_name_uk").detail())
                .isEqualTo("A workflow with this name exists.");
    }

    @Test
    void unknownOrMissingConstraintGetsTheGenericConflict() {
        ConstraintConflicts conflicts = ConstraintConflicts.none();

        assertThat(conflicts.conflictFor("anything").errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(conflicts.conflictFor(null).detail()).isEqualTo(ConstraintConflicts.DEFAULT_DETAIL);
    }

    @Test
    void rejectsCodesThatAreNotConflicts() {
        assertThatThrownBy(() -> ConstraintConflicts.of("x_uk", ErrorCode.NOT_FOUND, "Not found."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankNamesAndDetailsAndDuplicates() {
        ConstraintConflicts conflicts = ConstraintConflicts.of("x_uk", ErrorCode.JOB_NAME_TAKEN, "Taken.");

        assertThatThrownBy(() -> ConstraintConflicts.of(" ", ErrorCode.JOB_NAME_TAKEN, "Taken."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConstraintConflicts.of("y_uk", ErrorCode.JOB_NAME_TAKEN, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conflicts.and("x_uk", ErrorCode.TEAM_NAME_TAKEN, "Taken."))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
