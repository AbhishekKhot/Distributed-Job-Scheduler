/**
 * Use cases of the team feature and the ports they need: the team and membership store, the usage reader, the audit
 * log and control-database transactions. Plain Java on top of the security library (keysets, session revocation) and
 * the rate limiter port; no Spring web, JDBC or Redis types.
 */
@NullMarked
package dev.kairos.api.team.application;

import org.jspecify.annotations.NullMarked;
