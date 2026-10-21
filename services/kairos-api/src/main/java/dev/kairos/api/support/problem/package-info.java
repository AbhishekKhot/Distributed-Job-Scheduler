/**
 * RFC 9457 problem details (LLD section 3.1): the {@link dev.kairos.api.support.problem.Problem} body, the
 * exception-to-problem mapping of the MVC layer, and a writer that servlet filters use before a controller is reached.
 * Bodies carry fixed, client-safe messages only; details are logged server-side.
 */
@NullMarked
package dev.kairos.api.support.problem;

import org.jspecify.annotations.NullMarked;
