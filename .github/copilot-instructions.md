# Project Guidelines

## Code Style

- Use Maven Wrapper for Java tasks: `./mvnw`.
- Java uses 4-space indentation and is formatted by Spotless during Maven builds.
- JSON/YAML/HTML/Markdown formatting follows Prettier rules in `.prettierrc` and `.editorconfig`.
- Preferred formatting commands:
  - `npm run prettier:check`
  - `npm run prettier:format`

## Architecture

- This is a JHipster-generated Spring Boot 4.0.6 reactive gateway (`net.jojoaddison`) with MongoDB (Mongock migrations) + Kafka, running on Spring WebFlux and Spring Cloud Gateway.
- It owns authentication and user/account management for the patient subsystem; the sibling `hc-patient-service` only validates the JWTs this app issues.
- All REST controllers should use Spring WebFlux return types (`Mono<T>`, `Flux<T>`); avoid blocking patterns. BlockHound is active in tests and will fail on blocking calls.
- Routing is discovery-driven: `/services/{serviceId}/**` is rewritten to `/**` downstream with the `JWTRelay` default filter. Do not add static route definitions without a reason.
- Keep layer boundaries aligned with ArchUnit rules in `src/test/java/net/jojoaddison/TechnicalStructureTest.java`:
  - `config`
  - `web` (REST controllers, filters)
  - `service` (optional)
  - `security`
  - `repository` (optional)
  - `domain`
- Put REST endpoints in `src/main/java/net/jojoaddison/web/rest` and business logic in `src/main/java/net/jojoaddison/service`.

## Build And Test

- Development run:
  - `./mvnw`
  - or `npm run app:start`
- Build for production:
  - `./mvnw -Pprod clean verify`
  - `./mvnw -Pprod,war clean verify`
- Unit/integration tests:
  - `./mvnw verify`
  - `npm run backend:unit:test`
- Quality checks:
  - `npm run backend:nohttp:test`
  - `./mvnw -Pprod clean verify sonar:sonar -Dsonar.login=admin -Dsonar.password=admin`

## Care angels and events (2026-08-19)

- `POST /api/care-angels` finds or creates the account a nominated care angel signs in with. An email
  that already has an account is granted `ROLE_ANGEL` rather than given a second one. The reset key is
  never returned to the caller — it goes to the nominee's inbox and nowhere else.
- Registration grants `ROLE_USER` + `ROLE_PATIENT`. Neither role authorizes access to a record; that is
  `hc-patient-service`'s business.
- `patient-events` carries the patient journey. This service publishes the two account events and
  consumes `CareDelegationChanged` to send the delegation mails.
- **Publishing and mailing must run off the event loop.** `Mono.fromRunnable(...).subscribeOn(boundedElastic())`
  — and put _all_ the work inside, not just the send: BlockHound caught `UUID.randomUUID()` blocking on
  a Netty thread. `MailService` cost a production incident to learn this.
- Resolving several authorities with `flatMap` into a shared `HashSet` is a race that passes alone and
  fails under load. Use `concatMap` + `collect`.
- `src/test/resources/config/application.yml` **replaces** the main config rather than merging with it.
  Mirror every configuration change into both, or it is untested.

## Conventions

- The build targets Java 25 (`java.version`, pinned via `maven.compiler.release`); the Maven Enforcer accepts JDK 17-25 (`[17,26)`). Maven must be >= 3.2.5.
- Use profile-driven runs/builds (`dev` default, `prod` for release artifacts).
- Integration test naming follows Maven defaults:
  - Unit tests: `*Test.java`
  - Integration tests: `*IT.java` or `*IntTest.java`
- Prefer existing npm scripts in `package.json` when they exist instead of ad-hoc shell commands.

## Environment Prerequisites

- Consul is required at `http://localhost:8500`; app startup fails without it.
- MongoDB and Kafka are required dependencies for local development.
- Useful service helpers:
  - `npm run docker:consul:up`
  - `npm run docker:db:up`
  - `npm run docker:kafka:up`
  - `npm run services:up`

## Key References

- See `CLAUDE.md` for the verified stack, package map, and command reference.
- See `patient-gateway.md` for the plan of record: open decisions, platform hygiene, auth/onboarding work, and the tracked test-coverage gaps.
- See `AGENTS.md` for standing quality/security/performance expectations.
- See `README.md` for the endpoint inventory, security rules, seed data, and Docker compose usage.
- Ignore `angular.json` (leftover, no client here). This repo has no deploy script: deployment lives in `hc-patient/deploy` (`kojoampia/hc-patient-ci`).
- See `pom.xml` for profiles, Java/Maven constraints, and test plugin setup.
- See `package.json` for standard local commands used by this repository.
