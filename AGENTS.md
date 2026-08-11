# Project Overview

Repo-wide guidelines for `hc-patient-gateway` (`patientGateway`) — the reactive JHipster gateway that authenticates users, owns account/user data, and routes to the patient microservices.

Read in this order: `CLAUDE.md` for the verified stack/architecture summary, `patient-gateway.md` for the plan of record (open decisions, hygiene backlog, test-coverage backlog), then this file for standing expectations. `README.md` carries the endpoint/security inventory.

Statements below are split between **current** (true of the code today) and **target** (what to move toward). Anything in the target column that is actually scheduled appears as a tracked item in `patient-gateway.md`.

## Code Quality and Style

- Follow SOLID and clean-code practices; keep controllers thin and put logic in `service`.
- Java is 4-space indented and formatted by Spotless during the Maven build; JSON/YAML/HTML/Markdown by Prettier (`npm run prettier:check|format`). Checkstyle rules live in `checkstyle.xml`.
- **Reactive discipline is the house rule:** return `Mono<T>`/`Flux<T>`, never call blocking APIs on the event loop, and never `block()` in production code. BlockHound is enabled in tests and will fail the build.
- No null dereferences; prefer `Optional`/empty-`Mono` semantics over sentinel values.
- Errors surface through the JHipster `web/rest/errors` translation layer so responses stay RFC 7807-shaped.
- Lombok is not a dependency — keep JHipster's generated accessors.
- Log with SLF4J/Logback. The CRLF log converter is configured; keep it in the path for anything that logs user-supplied values.
- Document non-obvious behavior with JavaDoc. OpenAPI is served by springdoc but **only when the `api-docs` profile is active**, and `/v3/api-docs/**` additionally requires `ROLE_ADMIN`.

## Architecture and Design

- Layered architecture (`web` → `service` → `repository` → `domain`) with boundaries enforced by ArchUnit in `TechnicalStructureTest`.
- Constructor injection everywhere; no static initialization blocks.
- Routing stays discovery-driven: `/services/{serviceId}/**` rewritten to `/**`, with `JWTRelay` as the default filter. A static route needs a written reason.
- DTOs (`service/dto`) and MapStruct mappers (`service/mapper`) mediate between `domain.User` and the wire — don't return `User` directly from a resource.
- Mongock (`config/dbmigrations`) owns schema and **authority** evolution; add a change unit rather than seeding from application code. **Accounts are the exception**: a change unit runs in every profile, so seeding one there ships its credentials to production. Development accounts belong in `DevSeedDataInitializer` (profile-gated), and the production administrator in `AdminBootstrapInitializer` (no default password).
- Kafka via Spring Cloud Stream (`broker/`). Today only the generated `sse-topic` binding exists; real domain topics are agreed cross-repo (see `patient-gateway.md`).
- **Target:** the gateway is the natural place for rate limiting and request quotas; neither is implemented yet.

## Security Considerations

- This service is the **JWT issuer** for the patient subsystem. Changes to token contents, expiry, or signing affect `hc-patient-service` and both clients.
- Keep signing keys out of source control: the committed `base64-secret` values in `application-dev.yml`/`application-prod.yml` are a known defect (they also disagree with the microservice's) and are scheduled for removal.
- Passwords are hashed by the configured `PasswordEncoder`; never log, echo, or persist raw credentials, activation keys, or reset keys.
- Authorities come from `AuthoritiesConstants` (`ROLE_ADMIN`, `ROLE_USER`, `ROLE_ANONYMOUS`). Adding roles means updating the seed migration, the JWT `auth` claim consumers, and both clients together.
- Keep the anonymous path list in `SecurityConfiguration` minimal and mirrored in `README.md`. Anything under `/api/admin/**`, `/management/**`, or the api-docs paths stays admin-only.
- Registration and password-reset flows depend on a working `JavaMailSender`; failures must not leak whether an account exists.
- Validate and sanitize input (`@Valid` on request bodies, Bean Validation on DTOs); treat login/email normalization (lowercase) as part of the contract.
- Health data means GDPR/HIPAA-style obligations: no PII in logs, metrics labels, or error payloads.
- Use TLS in deployed environments (`application-tls.yml`); keep dependencies patched.

## Performance

- Never block: a blocking call in a reactive chain stalls the whole event loop, which for a gateway means the entire subsystem.
- Paginate anything list-shaped (`/api/users`, `/api/admin/users` already do) and reject unknown sort properties.
- The HTTP client pool is configured at `spring.cloud.gateway.httpclient.pool.max-connections: 1000` — revisit it alongside any load test rather than per-route.
- Resilience4j circuit breaking is enabled for reactive Feign; keep timeouts explicit when adding downstream calls.
- Monitor via Actuator (`/management/**`, Prometheus and `gateway` endpoints exposed); profile before optimizing.
- Keep the service stateless so instances scale horizontally.

## Testing

- Unit tests `*Test.java`; integration tests `*IT.java`/`*IntTest.java` (surefire excludes the latter, failsafe includes them — select with `-Dit.test`).
- Use `@IntegrationTest` for anything needing the Spring context, `WebTestClient` for reactive endpoints, and the Testcontainers-backed Mongo/Kafka setup in `config/`.
- Keep architecture (`TechnicalStructureTest`) and security/JWT tests separate from endpoint tests.
- The open coverage backlog is Phase C of `patient-gateway.md` — prefer picking an item from there over inventing new test scaffolding.

## Technology Stack

- Java 25 target (`maven.compiler.release`), Enforcer `[17,26)`, Maven ≥ 3.2.5.
- Spring Boot 4.0.6, Spring Cloud 2025.1.1, `tech.jhipster:jhipster-framework` 9.0.0 (no full JHipster BOM — the app moved to Spring Boot 4 ahead of the generator).
- Spring WebFlux, Spring Cloud Gateway, Spring Security (JWT), reactive Spring Data MongoDB, Mongock 5.5.1, Spring Cloud Stream Kafka binder, Spring Cloud Consul, Resilience4j, MapStruct.
- JUnit 5, Mockito, ArchUnit, BlockHound, Testcontainers (MongoDB + Kafka).
- Docker Compose for local dependencies; Jib for images.
- Maven for the build; npm only for dev tooling and script shortcuts.
- No CI wired up (`.github/` has no workflows); `angular.json` is a stale leftover — see `CLAUDE.md`. This repo carries no deploy script; deployment lives in `hc-patient/deploy`.
