/**
 * Accounts (LLD section 3.3, APIs 1 to 7): signup, login, token refresh, logout, the caller's profile, password change
 * and the JWKS document. This package holds the feature's Spring wiring and settings; the use cases and their ports
 * are in {@code application}, the SQL adapters in {@code infrastructure} and the controllers in {@code web}.
 */
@NullMarked
package dev.kairos.api.accounts;

import org.jspecify.annotations.NullMarked;
