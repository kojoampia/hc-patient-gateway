# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Health Connect Patient Gateway (`patientGateway`) — a JHipster **gateway** application that fronts the patient subsystem: it authenticates users, owns all account/user data, and routes traffic to Consul-discovered microservices. Backend-only (`skipClient: true`, no `src/main/webapp`). Package root: `net.jojoaddison`. Server port `5505`.

Stack as actually configured in `pom.xml` / `.yo-rc.json`:

|                  |                                                                                                           |
| ---------------- | --------------------------------------------------------------------------------------------------------- |
| Java             | 25 (`java.version`, and `maven.compiler.release`); Maven Enforcer accepts JDK `[17,26)`, Maven ≥ 3.2.5    |
| Framework        | Spring Boot 4.0.6, Spring Cloud 2025.1.1, `tech.jhipster:jhipster-framework` 9.0.0 (no full JHipster BOM) |
| Web stack        | Spring WebFlux + Spring Cloud Gateway — **reactive throughout**                                           |
| Datastore        | MongoDB (`mongodb://localhost:27017/patientGateway`), Mongock 5.5.1 migrations                            |
| Messaging        | Kafka via Spring Cloud Stream (`confluentinc/cp-kafka:7.5.2` locally)                                     |
| Discovery/config | Consul; **refuses to start cleanly without it** at `http://localhost:8500`                                |
| Auth             | JWT — this service is the **issuer**                                                                      |
| Generator        | JHipster 8.3.0                                                                                            |

Companion docs in this repo:

- `patient-gateway.md` — **the plan of record**: open decisions, platform hygiene, auth/onboarding features, and the 16-item test-coverage backlog. Check it before starting new work.
- `AGENTS.md` — standing quality/security/performance expectations.
- `README.md` — full endpoint inventory, security rules, seed data, Docker workflows.

Sibling plans: `hc-patient-service/patient-api.md`, `hc-patient-dashboard/patient-web.md`.

## Role in the subsystem

```
browser (hc-patient-dashboard, ng serve :4200) → this gateway :5505 → Consul discovery
                                                    /services/hcpatientservice/** → hc-patient-service :8081
```

- Routing is **discovery-driven, not static**: the Gateway discovery locator exposes every registered service as `/services/{serviceId}/**` and rewrites the path to `/**` downstream. Adding hardcoded routes should be a deliberate exception.
- The default filter is `JWTRelay` (`security/jwt/JWTRelayGatewayFilterFactory`): it decodes the bearer token and forwards it downstream.
- This service owns user management; `hc-patient-service` runs with `skipUserManagement: true` and only validates tokens.
- **Known break:** the committed `base64-secret` here differs from the microservice's in both `application-dev.yml` and `application-prod.yml`, so a relayed token fails validation downstream until both are sourced from one env var / Consul KV entry. Tracked as Phase A in `patient-gateway.md`.
- Authorities are only `ROLE_ADMIN`, `ROLE_USER`, `ROLE_ANONYMOUS` — there is no `PATIENT`/`ANGEL` role yet despite what the subsystem blueprint assumes.

## Commands

### Prerequisites

```
npm run services:up            # MongoDB (27017) + Consul (8500) + Kafka (9092)
# or individually: npm run docker:consul:up | docker:db:up | docker:kafka:up
```

### Run / build

```
./mvnw                         # dev profile (= npm run app:start)
npm run backend:debug          # dev + JDWP on 8000
./mvnw -Pprod clean verify     # production jar (add ,war for a war)
npm run java:docker            # Jib image
```

### Test

```
./mvnw verify                                 # full suite (= npm run backend:unit:test, quieter)
./mvnw test -Dtest=SecurityUtilsUnitTest      # single unit test (surefire)
./mvnw verify -Dit.test=AuthenticateControllerIT   # single integration test (failsafe)
./mvnw verify -DskipITs                       # unit tests only
npm run backend:nohttp:test                   # checkstyle
npm run prettier:check | prettier:format
```

Surefire excludes `**/*IT*` and `**/*IntTest*`; failsafe owns them. `-Dtest=SomethingIT` matches nothing — use `-Dit.test`. Integration tests start Mongo and Kafka via Testcontainers (Docker required, `services:up` not needed). **BlockHound** (`config/JHipsterBlockHoundIntegration`) fails any test that blocks on a reactive thread.

## Architecture

Layer boundaries are enforced at build time by ArchUnit (`src/test/java/net/jojoaddison/TechnicalStructureTest.java`): `config → web → service → security → repository → domain`.

`src/main/java/net/jojoaddison/`

- `web/rest` — `AuthenticateController` (issues JWTs), `AccountResource`, `UserResource` (`/api/admin/users`), `PublicUserResource` (`/api/users`), `AuthorityResource`, `GatewayResource` (`/api/gateway/routes`), `PatientGatewayKafkaResource`; `web/rest/errors` for RFC 7807 translation; `web/rest/vm` for `LoginVM`, `ManagedUserVM`, `KeyAndPasswordVM`, `RouteVM`.
- `web/filter` — `SpaWebFilter`, `ModifyServersOpenApiFilter`.
- `service` — `UserService`, `MailService`, the account exception types, `service/dto` (`UserDTO`, `AdminUserDTO`, `PasswordChangeDTO`) and `service/mapper/UserMapper` (MapStruct).
- `repository` — `UserRepository`, `AuthorityRepository` (reactive Spring Data Mongo).
- `domain` — `User`, `Authority`, `AbstractAuditingEntity`.
- `security` — `AuthoritiesConstants`, `SecurityUtils`, `DomainUserDetailsService`, `UserNotActivatedException`, and `security/jwt/JWTRelayGatewayFilterFactory`.
- `config` — `SecurityConfiguration`, `SecurityJwtConfiguration`, `DatabaseConfiguration`, `ReactorConfiguration`, `WebConfigurer`, `config/dbmigrations/` — `InitialSetupMigration`, `PatientRolesMigration` and `ProfessionalRoleMigration` seed **authorities only, in every profile**; `DevSeedDataInitializer` seeds the `admin`/`user`/`patient`/`angel`/`doctor` accounts under `dev` and `test` only, **and, when `hc.seed.location` names one, an account per person in that seed document** — the same file the patient service reads for its clinical half, which takes its `users` array and ignores the rest as this class ignores the collections. Those accounts' authorities come from the document rather than from the class, so a record with a care angel in it produces a `ROLE_ANGEL` account without anything here knowing that role exists, and the two halves join on `<login>@localhost`. It is unset in this repo; `hc-patient-quality` sets it; `AdminBootstrapInitializer` creates the first administrator in any profile from `gateway.admin.password`, which has no default. Do not move account seeding back into a change unit — Mongock has no notion of a profile, which is how production came to accept a derived admin password. Each role gets its own change unit rather than being added to an existing one, because Mongock records a change unit as executed and never runs it again — an already-migrated database would otherwise never receive the new role. `doctor` is the only account anywhere holding `ROLE_PROFESSIONAL`, which the patient service treats as unrestricted cross-patient access; nothing grants it in production.
- `broker` — `KafkaConsumer`/`KafkaProducer`; `management` — `SecurityMetersService`; `aop/logging` — logging aspect.

Security rules, the anonymous/admin path lists, seed data, and the endpoint inventory are documented in `README.md` — keep it in sync when they change.

## Constraints

- **Reactive only.** Controllers and services return `Mono`/`Flux`; no blocking calls. BlockHound will fail the build.
- Java stays within the Enforcer range `[17,26)`; the build targets 25, pinned with `maven.compiler.release` so the API surface matches the bytecode level whichever JDK builds it.
- Don't bypass the JHipster alert-header/exception-translation conventions in `web/rest/errors`.
- Respect the ArchUnit layer boundaries.
- `angular.json` is an inert leftover (no client here). The stale `deploy.sh`/`build-deploy.sh` copied from the admin gateway were deleted on 2026-08-11 — deployment lives in `hc-patient/deploy` (`kojoampia/hc-patient-ci`) and nothing in this repo deploys itself.
- No CI workflows exist in `.github/`; the `ci:*` npm scripts are unused entry points.
- `patient-gw.log` is output from the workspace-level `start-patient.sh` helper.
