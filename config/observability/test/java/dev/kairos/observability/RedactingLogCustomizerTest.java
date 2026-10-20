package dev.kairos.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** Starts a minimal application with ECS console logging and checks what reaches stdout. */
@ExtendWith(OutputCaptureExtension.class)
class RedactingLogCustomizerTest {

    private static final String SECRET = "Sup3rS3cretValue42";

    private static final Logger LOG = LoggerFactory.getLogger(RedactingLogCustomizerTest.class);

    @Test
    void structuredLogLinesNeverContainCredentials(CapturedOutput output) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.application.name=kairos-redaction-test",
                        "logging.structured.format.console=ecs")
                .run()) {
            MDC.put("Authorization", "Bearer " + SECRET);
            MDC.put("runId", "0190f3c4-8d2a-7e01-9b55-2a6f1c0e4d10");
            try {
                LOG.info("calling target with password={} and { \"apiKey\" : \"{}\" }", SECRET, SECRET);
                LOG.atWarn().addKeyValue("refresh_token", SECRET).log("token refresh failed");
                LOG.error(
                        "connect failed",
                        new IllegalStateException(
                                "jdbc:postgresql://app:" + SECRET + "@db/kairos failed, password=" + SECRET));
            } finally {
                MDC.clear();
            }
            assertThat(context.isActive()).isTrue();
        }

        assertThat(output.getOut())
                .contains("calling target with password=***")
                .contains("0190f3c4-8d2a-7e01-9b55-2a6f1c0e4d10")
                .contains("token refresh failed")
                .contains("connect failed")
                .doesNotContain(SECRET);
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {}
}
