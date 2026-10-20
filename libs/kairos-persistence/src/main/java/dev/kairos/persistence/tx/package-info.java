/**
 * Transaction entry points for repositories: {@link dev.kairos.persistence.tx.CellTransactions} runs a unit of work
 * on the cell of a shard, and {@link dev.kairos.persistence.tx.ControlTransactions} on the control database. Neither
 * lets one transaction span two databases.
 */
package dev.kairos.persistence.tx;
