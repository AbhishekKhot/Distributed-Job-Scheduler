/**
 * The web side of authentication: the bearer filter in the Spring Security chain, the 401 entry point, the
 * {@link dev.kairos.api.security.UserTokenOnly} check and the argument resolver that hands controllers their
 * {@link dev.kairos.api.security.TeamContext} or principal.
 */
@NullMarked
package dev.kairos.api.security.web;

import org.jspecify.annotations.NullMarked;
