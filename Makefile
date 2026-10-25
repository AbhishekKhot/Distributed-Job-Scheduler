# Developer entry points. Everything runs in containers: Gradle runs inside the pinned JDK 25
# builder image and the stack runs in Docker Compose, so the host needs only Docker, make,
# bash and openssl.

SHELL := /bin/bash
.DEFAULT_GOAL := help

JDK_IMAGE := eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4
COMPOSE   := docker compose --file deploy/compose/docker-compose.yml --env-file deploy/compose/.env

# Gradle in a container. The Gradle home (distribution and dependency cache) lives in a named
# volume. The Docker socket is mounted so Testcontainers can start Postgres, Kafka and Redis
# next to the build container.
GRADLE_OPTS_DOCKER := \
	--volume "$(CURDIR)":/workspace \
	--volume kairos-gradle-home:/gradle-home \
	--volume /var/run/docker.sock:/var/run/docker.sock \
	--add-host host.docker.internal:host-gateway \
	--env GRADLE_USER_HOME=/gradle-home \
	--env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	--workdir /workspace \
	--entrypoint ./gradlew
GRADLE_ARGS := --no-daemon --console=plain

# Foreground run, removed on exit.
GRADLE := docker run --rm --tty $(GRADLE_OPTS_DOCKER) $(JDK_IMAGE) $(GRADLE_ARGS)
# Detached run in a named container, kept after exit so its logs and exit code can be read.
BG_NAME := kairos-gradle
GRADLE_BG := docker run --detach --name $(BG_NAME) $(GRADLE_OPTS_DOCKER) $(JDK_IMAGE) $(GRADLE_ARGS)

.PHONY: help env build test gradle bg bg-logs bg-wait locks images up up-all down ps logs clean partitions

help: ## List the targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-10s %s\n", $$1, $$2}'

env: ## Write deploy/compose/.env with random credentials (once)
	@bash scripts/dev-env.sh

build: ## Compile, run unit tests and package every module
	$(GRADLE) build

test: ## Run all checks, including Testcontainers integration tests
	$(GRADLE) check

gradle: ## Run any Gradle command, e.g. make gradle ARGS=":libs:kairos-domain:test"
	$(GRADLE) $(ARGS)

bg: ## Run a Gradle command in a detached container, e.g. make bg ARGS=check
	@docker rm --force $(BG_NAME) >/dev/null 2>&1 || true
	@$(GRADLE_BG) $(ARGS) >/dev/null
	@echo "Started $(BG_NAME). Follow: make bg-logs   Result: make bg-wait"

bg-logs: ## Follow the output of the detached Gradle run
	docker logs --follow $(BG_NAME)

bg-wait: ## Wait for the detached Gradle run and exit with its status
	@status=$$(docker wait $(BG_NAME)); docker logs --tail 30 $(BG_NAME); exit $$status

locks: ## Refresh dependency lockfiles and checksums after changing a dependency
	$(GRADLE) --write-locks --write-verification-metadata sha256 resolveAndLockAll build

images: env ## Build every application image
	$(COMPOSE) --profile bench --profile maintenance --profile tools build

up: env ## Build and start the stack (API on http://127.0.0.1:8080)
	$(COMPOSE) up --build --detach --wait

up-all: env ## Same as up, plus Prometheus (127.0.0.1:9090) and Grafana (127.0.0.1:3000)
	$(COMPOSE) --profile observability up --build --detach --wait

partitions: env ## Run UTC partition creation and retention once with schema-owner credentials
	$(COMPOSE) --profile maintenance run --build --rm kairos-partitions

down: ## Stop the stack and keep the data volumes
	$(COMPOSE) --profile observability --profile bench --profile maintenance --profile tools down

ps: ## Show container status
	$(COMPOSE) --profile observability ps

logs: ## Follow logs, e.g. make logs S=kairos-executor
	$(COMPOSE) logs --follow $(S)

clean: ## Stop the stack and delete its data volumes and the Gradle cache volume
	$(COMPOSE) --profile observability --profile bench --profile maintenance --profile tools down --volumes --remove-orphans
	-docker volume rm kairos-gradle-home
