package dev.kairos.engine.lock;

/**
 * The run lock's Lua scripts. Each runs atomically on the Redis server, so a check and the change that depends on it
 * cannot interleave with another client. {@code KEYS[1]} is the lock key and {@code ARGV[1]} the caller's owner token.
 */
enum LuaScript {

    /** {@code SET key token NX PX ttl}; returns 1 if taken, 0 if someone holds the lock. {@code ARGV[2]} is the TTL. */
    ACQUIRE("acquire", """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
              return 1
            end
            return 0
            """),

    /** Returns the TTL left in milliseconds if the caller owns the lock, otherwise -2. */
    REMAINING("remaining", """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PTTL', KEYS[1])
            end
            return -2
            """),

    /** Compare-and-delete; returns 1 if deleted, 0 if the caller did not own the lock. */
    RELEASE("release", """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """),

    /** Compare-and-extend; returns 1 if the TTL was reset to {@code ARGV[2]} milliseconds, 0 if not owned. */
    EXTEND("extend", """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """);

    private final String label;
    private final String source;

    LuaScript(String label, String source) {
        this.label = label;
        this.source = source;
    }

    /** Returns the {@code script} metric label. */
    String label() {
        return label;
    }

    /** Returns the Lua source sent with {@code SCRIPT LOAD}. */
    String source() {
        return source;
    }
}
