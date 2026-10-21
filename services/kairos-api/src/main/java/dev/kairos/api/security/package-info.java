/**
 * Authentication of API callers (HLD section 7): the {@link dev.kairos.api.security.KairosPrincipal} built from a
 * verified access token or API key, the session and API key checks behind it, and the {@link
 * dev.kairos.api.security.TeamContext} that team-scoped operations receive. The team always comes from the credential,
 * never from the request.
 */
@NullMarked
package dev.kairos.api.security;

import org.jspecify.annotations.NullMarked;
