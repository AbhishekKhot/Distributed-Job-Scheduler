/**
 * Use cases of the accounts feature and the ports they need: the user store, the audit log and control-database
 * transactions. Plain Java on top of the security library (password hashing, access and refresh tokens) and the rate
 * limiter port; no Spring web, JDBC or Redis types.
 */
@NullMarked
package dev.kairos.api.accounts.application;

import org.jspecify.annotations.NullMarked;
