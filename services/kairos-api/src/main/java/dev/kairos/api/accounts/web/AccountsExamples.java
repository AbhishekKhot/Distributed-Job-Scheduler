package dev.kairos.api.accounts.web;

/** The OpenAPI examples of the accounts APIs, copied from LLD section 3.3. */
final class AccountsExamples {

    static final String SIGNUP_REQUEST = """
            {"email": "asha@example.com", "password": "<password, 12-128 characters>"}""";

    static final String SIGNUP_RESPONSE = """
            {"userId": "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b", "email": "asha@example.com", "teamId": null, \
            "createdAt": "2026-10-14T09:30:00Z"}""";

    static final String SIGNUP_VALIDATION = """
            {"type": "/errors/validation", "title": "Invalid request", "status": 400, \
            "detail": "Password must be 12 to 128 characters and not a common password.", \
            "errors": [{"field": "password", "code": "too-short"}]}""";

    static final String EMAIL_TAKEN = """
            {"type": "/errors/email-taken", "title": "Email already registered", "status": 409, \
            "detail": "An account with this email already exists."}""";

    static final String SIGNUP_RATE_LIMITED = """
            {"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, \
            "detail": "Too many signups from this address. Retry later."}""";

    static final String LOGIN_REQUEST = SIGNUP_REQUEST;

    static final String LOGIN_RESPONSE = """
            {"accessToken": "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig", \
            "tokenType": "Bearer", "expiresIn": 900, "refreshToken": "rt_q3V9mZ2xK7pL4sN8wB1cD6fH0jR5tY2u", \
            "refreshExpiresIn": 2592000, "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f"}""";

    static final String LOGIN_VALIDATION = """
            {"type": "/errors/validation", "title": "Invalid request", "status": 400, \
            "detail": "One or more fields are invalid.", "errors": [{"field": "email", "code": "required"}]}""";

    static final String INVALID_CREDENTIALS = """
            {"type": "/errors/invalid-credentials", "title": "Login failed", "status": 401, \
            "detail": "Invalid email or password."}""";

    static final String LOGIN_RATE_LIMITED = """
            {"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, \
            "detail": "Too many login attempts. Retry later."}""";

    static final String REFRESH_REQUEST = """
            {"refreshToken": "rt_q3V9mZ2xK7pL4sN8wB1cD6fH0jR5tY2u"}""";

    static final String REFRESH_RESPONSE = """
            {"accessToken": "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig", \
            "tokenType": "Bearer", "expiresIn": 900, "refreshToken": "rt_N4bV7cX1zL8kJ2hG5fD9sA3pO6iU0yT1", \
            "refreshExpiresIn": 2592000, "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f"}""";

    static final String INVALID_REFRESH_TOKEN = """
            {"type": "/errors/invalid-refresh-token", "title": "Refresh failed", "status": 401, \
            "detail": "The refresh token is invalid, expired or already used."}""";

    static final String LOGOUT_REQUEST = """
            {"refreshToken": "rt_N4bV7cX1zL8kJ2hG5fD9sA3pO6iU0yT1"}""";

    static final String LOGOUT_VALIDATION = """
            {"type": "/errors/validation", "title": "Invalid request", "status": 400, \
            "detail": "One or more fields are invalid.", "errors": [{"field": "refreshToken", "code": "required"}]}""";

    static final String PROFILE_RESPONSE = """
            {"userId": "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b", "email": "asha@example.com", \
            "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f", "teamName": "payments", \
            "createdAt": "2026-10-14T09:30:00Z"}""";

    static final String ACCESS_TOKEN_REQUIRED = """
            {"type": "/errors/unauthenticated", "title": "Authentication required", "status": 401, \
            "detail": "A valid access token is required."}""";

    static final String CHANGE_PASSWORD_REQUEST = """
            {"currentPassword": "<password, 12-128 characters>", \
            "newPassword": "<new password, 12-128 characters>"}""";

    static final String CHANGE_PASSWORD_VALIDATION = """
            {"type": "/errors/validation", "title": "Invalid request", "status": 400, \
            "detail": "Password must be 12 to 128 characters and not a common password.", \
            "errors": [{"field": "newPassword", "code": "too-short"}]}""";

    static final String INVALID_CURRENT_PASSWORD = """
            {"type": "/errors/invalid-current-password", "title": "Password not changed", "status": 400, \
            "detail": "The current password is incorrect."}""";

    static final String CHANGE_PASSWORD_RATE_LIMITED = """
            {"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, \
            "detail": "Too many attempts. Retry later."}""";

    static final String JWKS_RESPONSE = """
            {"keys": [{"kty": "EC", "crv": "P-256", "kid": "k202610", "use": "sig", "alg": "ES256", \
            "x": "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU", "y": "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}]}""";

    private AccountsExamples() {}
}
