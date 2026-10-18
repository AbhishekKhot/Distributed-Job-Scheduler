# Shared image definition for every Kairos application (services, jobs and tools).
#
#   docker build -f deploy/docker/Dockerfile --build-arg MODULE_PATH=services/kairos-api -t kairos-api .
#
# The "build" stage does not use MODULE_PATH, so BuildKit runs it once and reuses it for every
# image built from the same sources. Gradle checks each dependency against
# gradle/verification-metadata.xml, and the gradle.lockfile files fix the versions.

ARG JDK_IMAGE=eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4
ARG JRE_IMAGE=eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab

# ---------------------------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS build
WORKDIR /workspace
ENV GRADLE_USER_HOME=/gradle-home

# The wrapper and build logic rarely change; copying them first keeps their layers cached.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY libs libs
COPY services services
COPY jobs jobs
COPY tools tools

# Tests run separately (make test). The image build only packages the boot jars.
RUN --mount=type=cache,id=kairos-gradle-home,target=/gradle-home \
    ./gradlew --no-daemon --console=plain bootJar

# ---------------------------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS extract
ARG MODULE_PATH
RUN test -n "${MODULE_PATH}" || (echo "MODULE_PATH build argument is required" >&2 && exit 1)
WORKDIR /extract
COPY --from=build /workspace/${MODULE_PATH}/build/libs/app.jar app.jar
# Split the jar into layers so that a code change does not invalidate the dependency layer.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination /extracted

# ---------------------------------------------------------------------------------------------
FROM ${JRE_IMAGE} AS runtime
ARG MODULE_PATH
LABEL org.opencontainers.image.title="kairos" \
      org.opencontainers.image.description="Kairos distributed job scheduler: ${MODULE_PATH}" \
      org.opencontainers.image.source="https://github.com/AbhishekKhot/Distributed-Job-Scheduler"

# A fixed non-root identity, matching runAsUser in the Helm chart.
RUN groupadd --system --gid 10001 kairos \
 && useradd --system --uid 10001 --gid kairos --no-create-home --home-dir /nonexistent \
      --shell /usr/sbin/nologin kairos

WORKDIR /app
# The application files stay owned by root, so the process cannot modify its own code.
COPY --from=extract /extracted/dependencies/ ./
COPY --from=extract /extracted/spring-boot-loader/ ./
COPY --from=extract /extracted/snapshot-dependencies/ ./
COPY --from=extract /extracted/application/ ./

USER 10001:10001
# The heap follows the container memory limit. An OutOfMemoryError restarts the container
# instead of leaving a half-working JVM running.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080 8081
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
