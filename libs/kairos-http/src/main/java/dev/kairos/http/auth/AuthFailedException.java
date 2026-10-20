package dev.kairos.http.auth;

import dev.kairos.domain.retry.CallResult;
import java.io.Serial;
import java.util.Objects;

/**
 * Credentials could not be obtained for a call, for example because the OAuth 2.0 token endpoint failed. The target
 * itself was never contacted.
 *
 * <p>{@link #callResult()} is what the outcome classifier should see: the token endpoint's HTTP status when it
 * answered (a 401 there means bad client credentials and is not retried; a 5xx is), its network failure otherwise
 * (always with {@code requestSent = false}, because the target request was not sent), or
 * {@link CallResult.RenderFailed} when the token response is unusable. The message is fixed and secret-free.
 */
public final class AuthFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The classifier input; transient because results are not serialised. */
    private final transient CallResult callResult;

    /**
     * Creates the exception.
     *
     * @param callResult the classifier input
     * @param detail a fixed, secret-free description
     */
    public AuthFailedException(CallResult callResult, String detail) {
        super(detail);
        this.callResult = Objects.requireNonNull(callResult, "callResult");
    }

    /**
     * Returns the classifier input.
     *
     * @return the call result
     */
    public CallResult callResult() {
        return callResult;
    }
}
