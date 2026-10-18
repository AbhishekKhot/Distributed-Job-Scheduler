package dev.kairos.testsupport;

import java.io.IOException;
import java.util.Map;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Single-broker Kafka (KRaft) test container with every {@code kairos.run.*} topic created.
 *
 * <p>The container runs the same image as the {@code kafka} service in {@code deploy/compose/docker-compose.yml},
 * with automatic topic creation turned off as in compose. After the broker starts, the compose topic script
 * {@code deploy/compose/kafka/create-topics.sh} is executed inside the container. Running the script itself, rather
 * than parsing its {@code create} lines into admin-client calls, keeps a single definition of the topics: the same
 * {@code kafka-topics.sh} invocations, partition counts, retention and {@code min.insync.replicas} as the local
 * stack, with no second parser of a shell file that could drift from it. The price is one short-lived
 * {@code kafka-topics.sh} JVM per topic, about eight seconds on an idle machine, paid once per test JVM.
 *
 * <p>One container is started lazily per JVM, on the first call to {@link #get()}, and shared by every test class of
 * that run. It is never reused across runs. Tests that share it should use their own consumer group ids and unique
 * record keys.
 *
 * <p>Thread-safe.
 */
public final class KairosKafka {

    /** The image reference, identical to the {@code kafka} and {@code kafka-init} services in compose. */
    public static final String IMAGE =
            "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";

    private static final String TOPICS_SCRIPT = "deploy/compose/kafka/create-topics.sh";
    private static final String TOPICS_SCRIPT_IN_CONTAINER = "/opt/kairos/create-topics.sh";

    /**
     * The Testcontainers Kafka module adds a {@code BROKER} listener on port 9093 that advertises the container's own
     * host name, so a client inside the container can reach the broker on {@code localhost:9093}.
     */
    private static final String IN_CONTAINER_BOOTSTRAP = "localhost:9093";

    private static final Lazy<KairosKafka> INSTANCE = new Lazy<>(KairosKafka::start);

    private final String bootstrapServers;

    private KairosKafka(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Returns the shared container, starting it and creating the topics on first use.
     *
     * @return the running container
     * @throws IllegalStateException if the container cannot be started or the topics cannot be created, now or in an
     *     earlier attempt of this run
     */
    public static KairosKafka get() {
        return INSTANCE.get();
    }

    private static KairosKafka start() {
        KafkaContainer container = new KafkaContainer(PinnedImage.parse(IMAGE).toDockerImageName("apache/kafka"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(RepositoryFiles.resolve(TOPICS_SCRIPT), 0555),
                        TOPICS_SCRIPT_IN_CONTAINER);
        container.start();
        try {
            createTopics(container);
        } catch (RuntimeException e) {
            container.stop();
            throw e;
        }
        return new KairosKafka(container.getBootstrapServers());
    }

    private static void createTopics(KafkaContainer container) {
        ExecConfig exec = ExecConfig.builder()
                .command(new String[] {"bash", TOPICS_SCRIPT_IN_CONTAINER})
                .envVars(Map.of("KAFKA_BOOTSTRAP_SERVERS", IN_CONTAINER_BOOTSTRAP))
                .build();
        ExecResult result;
        try {
            result = container.execInContainer(exec);
        } catch (IOException e) {
            throw new IllegalStateException("Could not run " + TOPICS_SCRIPT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + TOPICS_SCRIPT, e);
        }
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    TOPICS_SCRIPT + " failed with exit code " + result.getExitCode() + ":\n" + result.getStderr());
        }
    }

    /**
     * Returns the bootstrap servers for clients running in the test JVM.
     *
     * @return a {@code host:port} pair
     */
    public String bootstrapServers() {
        return bootstrapServers;
    }
}
