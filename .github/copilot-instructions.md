# Project Guidelines

## Code Style

- Use Maven Wrapper for Java tasks: `./mvnw`.
- Java uses 4-space indentation and is formatted by Spotless during Maven builds.
- JSON/YAML/HTML/Markdown formatting follows Prettier rules in `.prettierrc` and `.editorconfig`.
- Preferred formatting commands:
  - `npm run prettier:check`
  - `npm run prettier:format`

## Architecture

- This is a JHipster Spring Boot reactive gateway (`net.jojoaddison`) with MongoDB + Kafka.
- All REST controllers should use Spring WebFlux return types (`Mono<T>`, `Flux<T>`); avoid blocking patterns.
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

## Conventions

- Java version must stay within JDK 17-21; Maven must be >= 3.2.5 (enforced in `pom.xml`).
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

- See `README.md` for operational workflows and Docker compose usage.
- See `pom.xml` for profiles, Java/Maven constraints, and test plugin setup.
- See `package.json` for standard local commands used by this repository.
