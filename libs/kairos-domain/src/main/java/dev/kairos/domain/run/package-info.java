/**
 * The run state machine.
 *
 * <p>{@link dev.kairos.domain.run.RunStatus} joins the unfinished states of {@code run_inflight}
 * with the final outcomes of {@code run_history}. {@link dev.kairos.domain.run.RunStateMachine}
 * holds the explicit table of allowed transitions, throws
 * {@link dev.kairos.domain.run.IllegalRunTransitionException} for any other, and applies the
 * {@link dev.kairos.domain.run.Fence} rule: every dispatch and every claim increments the fence.
 */
package dev.kairos.domain.run;
