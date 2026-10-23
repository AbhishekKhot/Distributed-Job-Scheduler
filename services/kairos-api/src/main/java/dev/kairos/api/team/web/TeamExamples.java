package dev.kairos.api.team.web;

/** The OpenAPI examples of the team APIs, copied from LLD section 3.4. */
final class TeamExamples {

    static final String CREATE_TEAM_REQUEST = """
            {"name": "payments"}""";

    private static final String QUOTAS = """
            "quotas": {"maxJobs": 10000, "maxExecPerSec": 2000, "maxInflight": 2000, "maxWorkflowTasks": 200, \
            "backfillPerSec": 100, "redrivePerSec": 100}""";

    static final String CREATE_TEAM_RESPONSE = "{\"teamId\": \"0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f\", "
            + "\"name\": \"payments\", " + QUOTAS + ", \"signingKey\": \"ksig_v1_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\", "
            + "\"createdAt\": \"2026-10-14T09:30:00Z\"}";

    static final String TEAM_VALIDATION = """
            {"type": "/errors/validation", "title": "Invalid request", "status": 400, \
            "detail": "Team name must match ^[a-z][a-z0-9-]{2,62}$.", \
            "errors": [{"field": "name", "code": "pattern"}]}""";

    static final String ALREADY_IN_TEAM = """
            {"type": "/errors/already-in-team", "title": "Already in a team", "status": 409, \
            "detail": "The user already belongs to a team."}""";

    static final String TEAM_NAME_TAKEN = """
            {"type": "/errors/team-name-taken", "title": "Team name taken", "status": 409, \
            "detail": "A team with this name already exists."}""";

    static final String TEAM_RATE_LIMITED = """
            {"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, \
            "detail": "Too many teams created. Retry later."}""";

    static final String TEAM_RESPONSE = "{\"teamId\": \"0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f\", "
            + "\"name\": \"payments\", " + QUOTAS + ", \"usage\": {\"jobs\": 1240, \"execPerSec\": 310, "
            + "\"inflight\": 42}, \"createdAt\": \"2026-10-14T09:30:00Z\"}";

    static final String MEMBERS_RESPONSE = """
            {"items": [{"userId": "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b", "email": "asha@example.com", \
            "addedAt": "2026-10-14T09:30:00Z"}], "nextCursor": null}""";

    static final String MEMBERS_VALIDATION = """
            {"type": "/errors/validation", "title": "Invalid request", "status": 400, \
            "detail": "One or more fields are invalid.", "errors": [{"field": "limit", "code": "range"}]}""";

    static final String ADD_MEMBER_REQUEST = """
            {"email": "ravi@example.com"}""";

    static final String ADD_MEMBER_RESPONSE = """
            {"userId": "0192e7c3-6b5a-7e9d-8c1f-2a3b4c5d6e7f", "email": "ravi@example.com", \
            "addedAt": "2026-10-14T09:30:00Z"}""";

    static final String ADD_MEMBER_VALIDATION = """
            {"type": "/errors/validation", "title": "Invalid request", "status": 400, \
            "detail": "One or more fields are invalid.", "errors": [{"field": "email", "code": "format"}]}""";

    static final String USER_NOT_FOUND = """
            {"type": "/errors/user-not-found", "title": "User not found", "status": 404, \
            "detail": "No account exists for this email."}""";

    static final String USER_IN_ANOTHER_TEAM = """
            {"type": "/errors/user-in-another-team", "title": "User in another team", "status": 409, \
            "detail": "The user already belongs to another team."}""";

    static final String MEMBER_RATE_LIMITED = """
            {"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, \
            "detail": "Too many members added. Retry later."}""";

    static final String MEMBER_NOT_FOUND = """
            {"type": "/errors/not-found", "title": "Not found", "status": 404, \
            "detail": "The member does not exist."}""";

    static final String LAST_MEMBER = """
            {"type": "/errors/last-member", "title": "Last member", "status": 409, \
            "detail": "A team must keep at least one member."}""";

    private TeamExamples() {}
}
