/**
 * Core value types.
 *
 * <p>Validated names ({@link dev.kairos.domain.model.TeamSlug},
 * {@link dev.kairos.domain.model.EndpointName}, {@link dev.kairos.domain.model.JobName},
 * {@link dev.kairos.domain.model.WorkflowName}, {@link dev.kairos.domain.model.TaskKey}),
 * {@link dev.kairos.domain.model.Priority} with its tiers, and every enumeration stored in a
 * {@code smallint} column ({@link dev.kairos.domain.model.CodedEnum}). Database codes are part of
 * the storage format and never change.
 */
package dev.kairos.domain.model;
