package dev.kairos.testsupport;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/**
 * Starts all three containers and checks that each matches the local stack: databases, roles and grants in Postgres,
 * topics in Kafka, and the ACL in Redis.
 */
@KairosIntegrationTest
class KairosContainersIntegrationTest {

    /** Partition counts from LLD section 4.2. */
    private static final Map<String, Integer> PARTITIONS = Map.of(
            "kairos.run.high", 24,
            "kairos.run.medium", 48,
            "kairos.run.low", 24,
            "kairos.run.result", 48,
            "kairos.run.cancel", 6,
            "kairos.run.completed", 12,
            "kairos.run.dead", 6);

    private static final long HOUR_MS = Duration.ofHours(1).toMillis();
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    /** Retention from LLD section 4.2. */
    private static final Map<String, Long> RETENTION_MS = Map.of(
            "kairos.run.high", DAY_MS,
            "kairos.run.medium", DAY_MS,
            "kairos.run.low", DAY_MS,
            "kairos.run.result", DAY_MS,
            "kairos.run.cancel", HOUR_MS,
            "kairos.run.completed", 3 * DAY_MS,
            "kairos.run.dead", 14 * DAY_MS);

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    // ---------------------------------------------------------------- Postgres

    @Test
    void applicationRoleConnectsToControlAndBothCells(KairosPostgres postgres) throws SQLException {
        Map<String, DataSource> databases = Map.of(
                "kairos_control", postgres.controlDataSource(),
                "kairos_cell0", postgres.cellDataSource(0),
                "kairos_cell1", postgres.cellDataSource(1));

        for (Map.Entry<String, DataSource> database : databases.entrySet()) {
            try (Connection connection = database.getValue().getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement("SELECT current_database(), current_user, current_schema()");
                    ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(database.getKey());
                assertThat(result.getString(2)).isEqualTo(KairosPostgres.APP_USERNAME);
                assertThat(result.getString(3)).isEqualTo("scheduler");
            }
        }
    }

    @Test
    void applicationRoleCannotCreateTables(KairosPostgres postgres) throws SQLException {
        for (DataSource dataSource :
                List.of(postgres.controlDataSource(), postgres.cellDataSource(0), postgres.cellDataSource(1))) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement("CREATE TABLE scheduler.t003_denied (id bigint)")) {
                assertThatThrownBy(statement::execute)
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                e -> assertThat(e.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            }
        }
    }

    @Test
    void applicationRoleGetsDmlOnTablesTheOwnerCreates(KairosPostgres postgres) throws SQLException {
        try (Connection owner = postgres.cellOwnerDataSource(1).getConnection()) {
            execute(owner, "CREATE TABLE scheduler.t003_probe (id bigint PRIMARY KEY, note text NOT NULL)");
            try (Connection app = postgres.cellDataSource(1).getConnection()) {
                try (PreparedStatement insert =
                        app.prepareStatement("INSERT INTO t003_probe (id, note) VALUES (?, ?)")) {
                    insert.setLong(1, 1L);
                    insert.setString(2, "written by sched_app");
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                try (PreparedStatement select = app.prepareStatement("SELECT note FROM t003_probe WHERE id = ?")) {
                    select.setLong(1, 1L);
                    try (ResultSet result = select.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo("written by sched_app");
                    }
                }
            } finally {
                execute(owner, "DROP TABLE scheduler.t003_probe");
            }
        }
    }

    @Test
    void ownerRoleOwnsTheSchedulerSchema(KairosPostgres postgres) throws SQLException {
        try (Connection connection = postgres.controlOwnerDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT n.nspowner::regrole::text FROM pg_namespace n WHERE n.nspname = ?")) {
            statement.setString(1, "scheduler");
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(KairosPostgres.OWNER_USERNAME);
            }
        }
    }

    // ---------------------------------------------------------------- Kafka

    @Test
    void createsEveryRunTopicWithTheDesignedPartitionsAndRetention(KairosKafka kafka)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()))) {
            assertThat(admin.listTopics().names().get(30, TimeUnit.SECONDS).stream()
                            .filter(name -> name.startsWith("kairos.run."))
                            .collect(toSet()))
                    .isEqualTo(PARTITIONS.keySet());

            Map<String, TopicDescription> topics =
                    admin.describeTopics(PARTITIONS.keySet()).allTopicNames().get(30, TimeUnit.SECONDS);
            assertThat(topics.values().stream()
                            .collect(toMap(
                                    TopicDescription::name,
                                    topic -> topic.partitions().size())))
                    .isEqualTo(PARTITIONS);

            List<ConfigResource> resources = PARTITIONS.keySet().stream()
                    .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name))
                    .toList();
            Map<ConfigResource, Config> configs =
                    admin.describeConfigs(resources).all().get(30, TimeUnit.SECONDS);
            assertThat(configs.entrySet().stream()
                            .collect(toMap(
                                    entry -> entry.getKey().name(),
                                    entry -> Long.parseLong(
                                            entry.getValue().get("retention.ms").value()))))
                    .isEqualTo(RETENTION_MS);
        }
    }

    @Test
    void producesAndConsumesOnARunTopic(KairosKafka kafka)
            throws ExecutionException, InterruptedException, TimeoutException {
        String topic = "kairos.run.cancel";
        String key = UUID.randomUUID().toString();
        Map<String, Object> producerConfig = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        Map<String, Object> consumerConfig = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);

        RecordMetadata sent;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig)) {
            sent = producer.send(new ProducerRecord<>(topic, key, "cancel")).get(30, TimeUnit.SECONDS);
        }

        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig)) {
            TopicPartition partition = new TopicPartition(topic, sent.partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, sent.offset());
            // The consumer is not thread-safe, so it is polled on the test thread.
            Awaits.await().pollInSameThread().until(() -> {
                consumer.poll(Duration.ofMillis(100)).forEach(received::add);
                return !received.isEmpty();
            });
        }

        assertThat(received.getFirst().key()).isEqualTo(key);
        assertThat(received.getFirst().value()).isEqualTo("cancel");
    }

    // ---------------------------------------------------------------- Redis

    @Test
    void aclUserCanWriteKairosKeys(KairosRedis redis) {
        withRedis(redis, commands -> {
            String key = "kairos:test:" + UUID.randomUUID();
            assertThat(commands.set(key, "x")).isEqualTo("OK");
            assertThat(commands.get(key)).isEqualTo("x");
            assertThat(commands.del(key)).isEqualTo(1L);
        });
    }

    @Test
    void aclUserCannotTouchKeysOutsideKairosOrRunDangerousCommands(KairosRedis redis) {
        withRedis(redis, commands -> {
            assertThatThrownBy(() -> commands.set("other:test:x", "x"))
                    .isInstanceOf(RedisCommandExecutionException.class)
                    .hasMessageStartingWith("NOPERM");
            assertThatThrownBy(commands::flushall)
                    .isInstanceOf(RedisCommandExecutionException.class)
                    .hasMessageStartingWith("NOPERM");
        });
    }

    @Test
    void defaultUserIsDisabled(KairosRedis redis) throws IOException {
        // A raw connection, so no client library authenticates or retries on our behalf.
        try (Socket socket = new Socket(redis.host(), redis.port())) {
            socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
            OutputStream out = socket.getOutputStream();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            sendCommand(out, "PING");
            assertThat(in.readLine()).startsWith("-NOAUTH");

            sendCommand(out, "AUTH", "default", redis.password());
            assertThat(in.readLine()).startsWith("-WRONGPASS");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void execute(Connection connection, String ddl) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.execute();
        }
    }

    private static void withRedis(KairosRedis redis, Consumer<RedisCommands<String, String>> body) {
        RedisURI uri = RedisURI.builder()
                .withHost(redis.host())
                .withPort(redis.port())
                .withAuthentication(redis.username(), redis.password())
                .withTimeout(Duration.ofSeconds(10))
                .build();
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            body.accept(connection.sync());
        } finally {
            client.shutdown();
        }
    }

    /** Writes one command in the RESP array format. */
    private static void sendCommand(OutputStream out, String... arguments) throws IOException {
        StringBuilder command = new StringBuilder();
        command.append('*').append(arguments.length).append("\r\n");
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            command.append('$')
                    .append(bytes.length)
                    .append("\r\n")
                    .append(argument)
                    .append("\r\n");
        }
        out.write(command.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
