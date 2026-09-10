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
- Authorities are `ROLE_ADMIN`, `ROLE_USER`, `ROLE_ANONYMOUS`, `ROLE_PATIENT`, `ROLE_ANGEL` and the eight clinical disciplines `ROLE_DOCTOR`/`NURSE`/`CARER`/`PARAMEDIC`/`PHARMACIST`/`THERAPIST`/`CHEMIST`/`TECHNICIAN` — spelled byte-identically to `hc-professional`'s gateway, which is the point of them. The blanket `ROLE_PROFESSIONAL` was removed on 2026-08-24 (change unit `004`). Registration grants **`ROLE_USER` + `ROLE_PATIENT`** and records `User.source` when the handoff link carried an allow-listed `src` (`HandoffSource`; the browser's copy of that allowlist is a convenience, this is the control, because `/api/register` is public); a nominated care angel's account additionally gets `ROLE_ANGEL`. Note what `ROLE_ANGEL` does _not_ do: an angel's authority to act for a patient comes from an `ACTIVE` `CareDelegation` that `hc-patient-service` re-reads on every request, never from the role. The role is for menus and for telling people apart.

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

#### When the whole suite goes red with one container

**Many red tests across unrelated classes, every stack trace ending in `MongoDbTestContainer.afterPropertiesSet` → `ReplicaSetInitializationException: A single node replica set was not initialized in a set timeout: 60 attempts`, is not a regression.** It is one Mongo container missing its start window on a loaded machine — `-Pprod clean verify` produced exactly that on 2026-09-05: 18 errors across `DomainUserDetailsServiceIT`, `UserServiceIT`, `PatientEventConsumerBindingIT` and `PlansRouteIT`, and a re-run of those four alone was green. Testcontainers waits 60 attempts 100 ms apart — about six seconds — and `AWAIT_INIT_REPLICA_SET_ATTEMPTS` is a `private static final int` inside `MongoDBContainer`, so the window cannot be widened from here. It spreads because `TestContainersSpringContextCustomizerFactory` assigns its static bean only _after_ the container has started: a failed start leaves it null, the next class builds its own container and gets its own six seconds to miss, and classes sharing the failed context report `ApplicationContext failure threshold (1) exceeded` without starting anything at all.

`MongoDbTestContainer` **retries the start three times**, discarding the container between attempts — a half-started one has a partly initialised replica set and restarting _that_ loops on `ReadConcernMajorityNotAvailableYet` rather than recovering. The confusing failure becomes a slow pass. A real failure (no Docker daemon, an image tag that does not exist) still throws after the third attempt, with an error pointing at `docs/backlog.md` item 9.

**Container reuse is the other lever, and it is yours rather than the repository's.** The fixture asks for `.withReuse(true)` and Testcontainers **silently ignores it** unless the machine opts in:

```
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties   # per machine
TESTCONTAINERS_REUSE_ENABLE=true ./mvnw verify                            # per run
```

With it on, one Mongo container survives across test classes and across runs: the suite is much faster locally and this contention largely stops happening. **Leave it off in CI.** A reused container carries state between runs and is not reaped, which is the wrong trade on an ephemeral runner — which is why it is a per-machine setting and there is nothing to commit here.

## Architecture

Layer boundaries are enforced at build time by ArchUnit (`src/test/java/net/jojoaddison/TechnicalStructureTest.java`): `config → web → service → security → repository → domain`.

`src/main/java/net/jojoaddison/`

- `web/rest` — `AuthenticateController` (issues JWTs), `AccountResource`, `CareAngelResource` (`/api/care-angels`), `UserResource` (`/api/admin/users`), `PublicUserResource` (`/api/users`), `AuthorityResource`, `GatewayResource` (`/api/gateway/routes`), `PatientGatewayKafkaResource`; `web/rest/errors` for RFC 7807 translation; `web/rest/vm` for `LoginVM`, `ManagedUserVM`, `KeyAndPasswordVM`, `RouteVM`.
- `web/filter` — `SpaWebFilter`, `ModifyServersOpenApiFilter`.
- `service` — `UserService`, `MailService`, `CareAngelService`, the account exception types, `service/dto` (`UserDTO`, `AdminUserDTO`, `PasswordChangeDTO`, `CareAngelAccountDTO`), `service/mapper/UserMapper` (MapStruct), and `service/event` (`PatientEventPublisher`, `CareDelegationMailer`).
- `repository` — `UserRepository`, `AuthorityRepository` (reactive Spring Data Mongo).
- `domain` — `User`, `Authority`, `AbstractAuditingEntity`.
- `security` — `AuthoritiesConstants`, `SecurityUtils`, `DomainUserDetailsService`, `UserNotActivatedException`, and `security/jwt/JWTRelayGatewayFilterFactory`.
- `config` — `SecurityConfiguration`, `SecurityJwtConfiguration`, `DatabaseConfiguration`, `ReactorConfiguration`, `WebConfigurer`, `config/dbmigrations/` — `InitialSetupMigration`, `PatientRolesMigration`, `ProfessionalRoleMigration` and `ClinicalDisciplineRolesMigration` seed **authorities only, in every profile** (`004` also replaces `ROLE_PROFESSIONAL` with `ROLE_DOCTOR` on any account that held it, and deletes the old authority); `DevSeedDataInitializer` seeds the `admin`/`user`/`patient`/`angel`/`doctor` accounts under `dev` and `test` only, **and, when `hc.seed.location` names one, an account per person in that seed document** — the same file the patient service reads for its clinical half, which takes its `users` array and ignores the rest as this class ignores the collections. Those accounts' authorities come from the document rather than from the class, so a record with a care angel in it produces a `ROLE_ANGEL` account without anything here knowing that role exists, and the two halves join on `<login>@localhost`. It is unset in this repo; `hc-patient-quality` sets it; `AdminBootstrapInitializer` creates the first administrator in any profile from `gateway.admin.password`, which has no default. Do not move account seeding back into a change unit — Mongock has no notion of a profile, which is how production came to accept a derived admin password. Each role gets its own change unit rather than being added to an existing one, because Mongock records a change unit as executed and never runs it again — an already-migrated database would otherwise never receive the new role. `doctor` is the only fixed account anywhere holding a clinical discipline — `ROLE_DOCTOR` since 2026-08-24, `ROLE_PROFESSIONAL` before it — which the patient service treats as unrestricted cross-patient access; nothing grants one in production.
- `broker` — `KafkaConsumer`/`KafkaProducer`; `management` — `SecurityMetersService`, `MailHealthMetrics`, `LoginMetersService` and `RegistrationMetersService`; `aop/logging` — logging aspect.

**The dashboard meters (2026-09-10, `docs/backlog.md` item 34) — read `patient-gateway.md` for what each number means.** Four series: `account.registrations{state}` (a **gauge**, the standing population sampled from `UserRepository.countByActivated` by `service/RegistrationMetricsSampler` every 60s — deliberately not counted from the `AccountCreated`/`AccountActivated` events, which restart from zero and cannot answer "how many are unactivated right now"), its `…sampled.timestamp` companion, `security.authentication.logins{outcome=success}` (a counter, **per attempt**) and `security.authentication.failed-logins{cause}` (a counter, **per account** on the 0 → 1 transition of `failedLoginAttempts` — except `account-locked`, which is per refused attempt). Two things here are load-bearing and easy to undo: **the `account-locked` increment is in `AuthenticateController` and cannot move into `LoginAttemptService.recordFailure`**, because a locked attempt short-circuits and never reaches that method — instrumenting only there goes silent for the whole lockout window; and **an unknown login is counted nowhere**, because a per-login side effect is an account-existence oracle. `LoginMetersIT` fails on both.

Security rules, the anonymous/admin path lists, seed data, and the endpoint inventory are documented in `README.md` — keep it in sync when they change.

## Care angels, and the event stream (2026-08-19)

`docs/onboarding.md` is the plan of record; §16 is the contract.

- **`POST /api/care-angels`** finds or creates the account a patient's nominated care angel signs in with. An email that already has an account is granted `ROLE_ANGEL` rather than given a second one. A new account is created **already activated** with a random UUID password nobody knows, and invited by the ordinary password-reset mail — which is what makes "cannot authenticate until they set a password" true without a new flag, and leaves `GET /api/activate`'s contract alone. The reset key is never returned to the caller; it goes to the nominee's inbox and nowhere else.
- **Login derivation:** `Grace Mensah` -> `ge_mensah`. Numeric suffix on collision, accents transliterated because `LOGIN_REGEX` permits only `[_.@A-Za-z0-9-]`, and a fallback to the email's local part when there is no usable surname.
- **`patient-events`** carries the whole patient journey. This service publishes `AccountCreated` and `AccountActivated`, and consumes `CareDelegationChanged` back off the same topic to send the delegation mails — only this service can send mail, and only `hc-patient-service` knows when a delegation changed.
- **Nothing here authorizes a care angel.** `ROLE_ANGEL` is informational; the authority to act for a patient is an `ACTIVE` `CareDelegation` that `hc-patient-service` re-reads per request.

Three traps, all of which cost time on the way in:

- **`src/test/resources/config/application.yml` is the same classpath resource as the main one** and replaces it wholesale rather than merging. Anything configured only in main is configured for production and for nothing any test can see. Three separate defects came from this — a stream binding, a Kafka key serializer, and the Abofonsa route — each with a green suite. **Mirror every configuration change into both files.**
- **BlockHound earns its keep.** It caught `PatientEventPublisher` building its envelope on a Netty event loop, because `UUID.randomUUID()` draws on `SecureRandom` and can block. The whole publish runs on `boundedElastic`, not just the send.
- **`flatMap` into a shared `HashSet` is a race** that passes alone and fails in a full class run. Both places that resolve authorities use `concatMap` + `collect` for that reason.

## Constraints

- **Reactive only.** Controllers and services return `Mono`/`Flux`; no blocking calls. BlockHound will fail the build.
- Java stays within the Enforcer range `[17,26)`; the build targets 25, pinned with `maven.compiler.release` so the API surface matches the bytecode level whichever JDK builds it.
- Don't bypass the JHipster alert-header/exception-translation conventions in `web/rest/errors`.
- Respect the ArchUnit layer boundaries.
- **No client scaffolding remains.** `angular.json`, `webpack/` and `jest.conf.js` were deleted on 2026-08-30 (`skipClient: true`, no `src/main/webapp`, nothing read them — `jest.conf.js` was the sole reader of `webpack/`, and it had no `jest` dependency behind it either). `tsconfig.spec.json` is the last of the set and stays for now: `tsconfig.json` names it in `references`. The stale `deploy.sh`/`build-deploy.sh` copied from the admin gateway were deleted on 2026-08-11 — deployment lives in `hc-patient/deploy` (`kojoampia/hc-patient-ci`) and nothing in this repo deploys itself.
- **CI runs on every push and pull request** — `.github/workflows/build.yml` (`./mvnw verify` + dependency scan) and `release.yml` (GHCR publish on main), both since 2026-08-05. This line said no workflows existed until 2026-08-31. The `ci:*` npm scripts genuinely are unused: the workflow calls `./mvnw` directly.
- **`bin/` is your IDE's, and it outlives deletions.** An Eclipse output directory reappears here if you open the project in one; it was removed again on 2026-08-31. Worth knowing why it is not just clutter: the sibling `hc-patient-service` had the self-signed `keystore.p12` that `977cf09` deleted for security still sitting in its `bin/`, twenty-six days later. This repo's equivalent cleanup (`46f56e7`) left only an empty `config/tls/` directory behind, so nothing survived here — but check before assuming, because **deleting a file from a repository does not delete it from the machines that have it.**
- `patient-gw.log` is output from the workspace-level `start-patient.sh` helper.
