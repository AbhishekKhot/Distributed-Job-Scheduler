package dev.kairos.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Redis test container with the same ACL as the local stack.
 *
 * <p>The container runs the same image as the {@code redis} service in {@code deploy/compose/docker-compose.yml} and
 * starts through the same script, {@code deploy/compose/redis/start-redis.sh}, as the {@code redis} user. The default
 * user is disabled, and the only login is {@value #USERNAME}, limited to {@code kairos:*} keys and channels and barred
 * from dangerous commands. Its password is 64 hex characters generated for every run.
 *
 * <p>One container is started lazily per JVM, on the first call to {@link #get()}, and shared by every test class of
 * that run. It is never reused across runs. Tests that share it must use unique keys under {@code kairos:}.
 *
 * <p>Thread-safe.
 */
public final class KairosRedis {

    /** The image reference, identical to the {@code redis} service in compose. */
    public static final String IMAGE =
            "redis:8.8.3@sha256:eb1aa641bc5380ed14442398e45a5a51aeaaac919fab811e4f134eb77c71dd2d";

    /** The ACL user the services log in with. */
    public static final String USERNAME = "kairos";

    private static final String START_SCRIPT = "deploy/compose/redis/start-redis.sh";
    private static final String START_SCRIPT_IN_CONTAINER = "/opt/kairos/start-redis.sh";
    private static final int REDIS_PORT = 6379;
    private static final Lazy<KairosRedis> INSTANCE = new Lazy<>(KairosRedis::start);

    private final String host;
    private final int port;
    private final String password;

    private KairosRedis(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    /**
     * Returns the shared container, starting it on first use.
     *
     * @return the running container
     * @throws IllegalStateException if the container cannot be started, now or in an earlier attempt of this run
     */
    public static KairosRedis get() {
        return INSTANCE.get();
    }

    private static KairosRedis start() {
        String password = TestSecrets.randomHex();
        GenericContainer<?> container = new GenericContainer<>(
                        PinnedImage.parse(IMAGE).toDockerImageName("redis"))
                .withExposedPorts(REDIS_PORT)
                .withEnv("KAIROS_REDIS_PASSWORD", password)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(RepositoryFiles.resolve(START_SCRIPT), 0444),
                        START_SCRIPT_IN_CONTAINER)
                // The same entry point and user as compose; the script writes the ACL file and execs redis-server.
                .withCreateContainerCmdModifier(cmd ->
                        cmd.withEntrypoint("sh", START_SCRIPT_IN_CONTAINER).withUser("redis"))
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\s", 1));
        container.start();
        return new KairosRedis(container.getHost(), container.getMappedPort(REDIS_PORT), password);
    }

    /**
     * Returns the host name clients in the test JVM connect to.
     *
     * @return the host name
     */
    public String host() {
        return host;
    }

    /**
     * Returns the port clients in the test JVM connect to.
     *
     * @return the mapped port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the ACL user name.
     *
     * @return {@value #USERNAME}
     */
    public String username() {
        return USERNAME;
    }

    /**
     * Returns the password of the ACL user, generated for this run.
     *
     * @return the password; never log it
     */
    public String password() {
        return password;
    }
}
