package dev.kairos.api.apikeys.web;

/** Static LLD examples; placeholder credentials only, never usable keys. */
final class ApiKeyExamples {
    static final String REQUEST = """
            {"name":"ci-pipeline","expiresAt":"2027-04-14T00:00:00Z"}
            """;
    static final String CREATED = """
            {"keyId":"7Hq2Lm9Xc4Vb8Nz1","name":"ci-pipeline",
             "key":"kairos_7Hq2Lm9Xc4Vb8Nz1_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
             "expiresAt":"2027-04-14T00:00:00Z","createdAt":"2026-10-14T09:30:00Z"}
            """;
    static final String LIST = """
            {"items":[{"keyId":"7Hq2Lm9Xc4Vb8Nz1","name":"ci-pipeline",
             "createdBy":"0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b","expiresAt":"2027-04-14T00:00:00Z",
             "lastUsedAt":"2026-10-14T09:30:00.084Z","revokedAt":null}],"nextCursor":null}
            """;
    static final String VALIDATION = """
            {"type":"/errors/validation","title":"Invalid request","status":400,
             "detail":"One or more fields are invalid.","errors":[{"field":"name","code":"length"}]}
            """;
    static final String FORBIDDEN = """
            {"type":"/errors/forbidden-for-api-key","title":"Not allowed for API keys","status":403,
             "detail":"This operation requires a user access token."}
            """;
    static final String NOT_FOUND = """
            {"type":"/errors/not-found","title":"Not found","status":404,
             "detail":"The API key does not exist."}
            """;

    private ApiKeyExamples() {}
}
