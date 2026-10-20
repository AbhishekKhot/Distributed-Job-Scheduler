/**
 * Fan-out reads for team-scoped lists: {@link dev.kairos.persistence.fanout.FanOutQuery} reads every cell in parallel
 * and merges the results k-way by sort key into one keyset-paginated
 * {@link dev.kairos.persistence.fanout.FanOutPage}.
 */
package dev.kairos.persistence.fanout;
