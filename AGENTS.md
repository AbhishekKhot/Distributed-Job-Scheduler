# Repository Guidelines

## Project Structure & Module Organization

Kairos is a Java 25 / Spring Boot 4 distributed HTTP job scheduler built with Gradle Kotlin DSL. `services/` contains the API, watcher, executor, and state services; `libs/` holds shared domain, persistence, messaging, security, HTTP, engine, Protobuf, and test-support modules. `jobs/` contains migrations and maintenance; `tools/` provides the mock target and benchmark CLI.

Modules use `src/main/java`, `src/main/resources`, and `src/test/java`. Shared build conventions live in `build-logic/`, architecture checks in `config/archunit/`, deployment files in `deploy/`, and diagrams in `assets/`.

## Build, Test, and Development Commands

Install Docker with Compose v2, make, bash, and openssl. Builds use a pinned JDK 25 container; no local JDK is required.

- `make env`: generate local credentials in `deploy/compose/.env`.
- `make up`: build images and start the local stack.
- `make build`: compile, run unit tests and quality gates, and package applications.
- `make test`: run all checks, including Testcontainers integration tests.
- `make gradle ARGS=":libs:kairos-domain:test"`: test a specific module.
- `make gradle ARGS=spotlessApply`: format Java and Gradle scripts.
- `make locks`: refresh dependency locks and checksums after dependency changes.

## Coding Style & Naming Conventions

Follow `.editorconfig`: four-space indentation, two spaces for YAML/JSON/TOML, tabs for Makefiles, UTF-8, and LF endings. Spotless applies Palantir Java Format and ktlint. Compilation warnings fail the build; libraries also use Error Prone and NullAway.

Use `dev.kairos.<service>.<feature>` packages with `web`, `application`, and `infrastructure` layers. Keep domain code framework-free and follow ArchUnit dependency rules. Use PascalCase types and camelCase methods. Document public library types and methods with Javadoc.

## Testing Guidelines

Use JUnit Jupiter, AssertJ, and shared helpers from `libs/kairos-test-support`. Name tests `*Test` and integration tests `*IntegrationTest`. Cover added SQL, Kafka flows, and Redis scripts with Testcontainers. JaCoCo enforces minimum line coverage of 80% for libraries and 70% for applications.

## Commit & Pull Request Guidelines

Use Conventional Commits with module scopes, matching history: `feat(api): ...`, `fix(engine): ...`, `test(domain): ...`. Create `t-<id>-<short-name>` branches and target `develop`. Keep each PR focused on one checklist task; list acceptance criteria and verification results. Update checklist items and document contract changes in `LLD.md` within the same PR. Consult `HLD.md` for architecture.

## Security & Configuration

Never commit secrets or generated `.env` files. Keep dependency versions in `gradle/libs.versions.toml` and refresh verification metadata through `make locks`.

## Project Context

Read [AGENT.md](AGENT.md) for architecture decisions, task status, workflow rules, and design references before starting work.
