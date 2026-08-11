# Patient Gateway — Plan

Single plan of record for `hc-patient-gateway` (`patientGateway`). It consolidates the test-coverage backlog that used to live in the now-deleted `missing-test-cases.md` with the gateway-side slices of the Health Connect patient blueprint/checklist and the repo hygiene items found while auditing this service.

- **Baseline verified:** 2026-08-03 against `pom.xml`, `.yo-rc.json`, `src/main/java`, `src/test/java`, `src/main/resources/config`. (Previous baseline 2026-07-30.)
- **Companion docs:** `CLAUDE.md` (what exists and how it is wired), `AGENTS.md` (standing expectations), `README.md` (endpoint inventory, security rules, seed data).
- **Sibling plans:** `hc-patient-service/patient-api.md`, `hc-patient-dashboard/patient-web.md`.

Status legend: `[x]` done · `[~]` partial / diverges from plan · `[ ]` not started.

## What changed since the last baseline

### The server port moved to 5505 (branch `chore/gateway-port-5505`, 2026-08-03)

The gateway listened on **5503** while the dashboard's dev proxy had always targeted **5505**
(`webpack/proxy.conf.js`, `webpack/environment.js`). `npm start` therefore reached nothing until a
developer discovered the mismatch — it was decision 1 in `hc-patient-dashboard/patient-web.md`.

Resolved by moving the gateway rather than the proxy, and moving it **everywhere** rather than in
dev only, so there is no dev/prod split left to trip over:

- this repo — `application-dev.yml` (`server.port` and `jhipster.mail.base-url`),
  `application-prod.yml`, the Jib container port in `pom.xml`, `.yo-rc.json` `serverPort`,
  `package.json` `config.backend_port`, `src/main/docker/app.yml`, the local Prometheus scrape
  target, and `.devcontainer/devcontainer.json`
- `hc-patient/deploy` — the four nginx upstreams in `docker/web-nginx.conf`, the compose port map,
  `docker/gateway.Dockerfile` (`EXPOSE` and its health check), `prod-server/compose.yml`'s health
  check, and the readiness probe in `deploy.sh`

`.yo-rc.json` was changed deliberately: it is the value the generator writes into both profiles, so
leaving it at 5503 would have let a regeneration silently undo this.

**This needs a deploy from `hc-patient/deploy` to take effect on `patient.abofonsa.com`.** Until the stack is
redeployed, the running web container's nginx still proxies to 5503 and the gateway image still
listens there — they are consistent with each other, so the site keeps working; it is only the
combination of a new image with an old nginx config (or vice versa) that would break. Deploy both
together, which `hc-patient/deploy/deploy.sh` does.

### The Java target moved 26 → 25 (2026-08-04)

`java.version` is now **25**, along with the Enforcer range (`[17,26)`), the Jib base image
(`eclipse-temurin:25-jre`) and `deploy/docker/gateway.Dockerfile`'s build and runtime stages.

What prompted it: the whole test suite failed with
`ServiceConfigurationError: ... BlockHoundTestExecutionListener could not be instantiated`, which
looks like a BlockHound problem and is not one. The project compiled to class-file **v70** (Java 26)
while `JAVA_HOME` pointed at a Java 25 **JRE** that reads at most v69, so the first project class the
JVM touched threw `UnsupportedClassVersionError`. BlockHound loads `JHipsterBlockHoundIntegration`
through `ServiceLoader` in its static initialiser, so it was simply first in line — the count was
`Tests run: 0`, not a set of failures.

Two things were changed beyond the version number:

- **`maven.compiler.release` is now set** (it was `source`/`target` only). Those set the bytecode
  level but still compile against the _building_ JDK's class library, so a build on a newer JDK can
  link an API the runtime does not have — the same class of mismatch, discovered at runtime instead
  of compile time. `release` pins the API surface too.
- Modernizer needed no change: its `<javaVersion>` already follows `${java.version}`. The 2.7.0 →
  3.5.0 bump stays — it was made because 2.7.0 could not read Java 26 bytecode, and 3.5.0 reads 25
  perfectly well.

Verified on a real JDK 25: `./mvnw clean verify` — **112 tests, 0 failures, 0 Checkstyle violations**,
and `BlockHoundTestExecutionListener` instantiates on the JRE 25 that previously could not load it.

For reference, BlockHound's own JDK constraint is unrelated to the above and already satisfied here:
from **JDK 13 onward it requires `-XX:+AllowRedefinitionToAddDeleteMethods`** (reactor/BlockHound#33),
which this pom sets in both the surefire and failsafe `argLine`. On JDK 26 it also warns that dynamic
agent loading will be disallowed in a future release and that it calls the terminally deprecated
`sun.misc.Unsafe::objectFieldOffset` — that is where BlockHound will eventually break, so check it
before the next JDK bump.

## Open decisions

1. **Patient/angel roles.** `ROLE_PATIENT` and `ROLE_ANGEL` now exist in `AuthoritiesConstants` and are seeded, but nothing consumes them yet: no route or method authorizes on them, `hc-patient-service` does not check them, and the dashboard's `Authority` enum still knows only ADMIN/USER. Decide what each role may actually do before wiring clients to it.
2. **Registration ownership.** Onboarding (basic info → identification → plan) spans this service's `POST /api/register` and the patient service's profile/subscription domain. Decide which service owns the transaction and what happens if the second step fails.
3. **Where rate limiting lives.** Neither service implements it. The gateway is the natural choke point; confirm before adding it downstream.

## Baseline — already in place

- `[x]` JWT authentication (`POST /api/authenticate`) and `GET /api/authenticate`.
- `[x]` Self-service account flows: register, activate, profile update, change password, reset password init/finish.
- `[x]` Admin user management (`/api/admin/users`), authority management (`/api/authorities`), public user listing (`/api/users`).
- `[x]` Discovery-based routing: `/services/{serviceId}/**` → `/**` downstream, with the `JWTRelay` default filter.
- `[x]` Route inspection (`GET /api/gateway/routes`) and the Kafka bridge (`/api/patient-gateway-kafka`).
- `[x]` Seeding, in three parts since the credentials fix (2026-08-02). Two idempotent Mongock change units seed **authorities only, in every profile**: `InitialSetupMigration` (001) `ROLE_USER`/`ROLE_ADMIN`, `PatientRolesMigration` (002) `ROLE_PATIENT`/`ROLE_ANGEL` — a second change unit rather than an edit to 001, so databases that already ran 001 still get the new roles. `DevSeedDataInitializer` seeds the `admin`/`user`/`patient`/`angel` accounts under `dev`/`test` only, with passwords derived per login by `SeedData`. `AdminBootstrapInitializer` creates the first administrator in any profile from `gateway.admin.password` (`GATEWAY_ADMIN_PASSWORD`), which has **no default**; unset, nothing is created and it warns. Passwords are never logged. The change units create no accounts because Mongock has no notion of a profile — see the incident note below.
- `[x]` Spring Boot 4.0.6 upgrade, reactive throughout, BlockHound active in tests. Java target moved 26 → **25** on 2026-08-04 (see below).

## Phase A — platform hygiene

> **Fixed 2026-08-02 — mail was blocking the event loop.** `MailService` wrapped each send in
> `Mono.defer(...).subscribe()` with no scheduler, which runs the work inline on the subscribing
> thread. Every caller is a reactive handler on a Netty event loop, and JavaMail is blocking, so the
> SMTP conversation ran _on the event loop_: measured at **2.8s on `ntLoopGroup-4-3`** against the
> real relay in production, stalling every other request on that thread. The code read as
> asynchronous, which is what kept it invisible.
>
> BlockHound did not catch it, and would not have: `MailServiceIT` mocks `JavaMailSender`, so no
> socket is opened and nothing blocks during the tests. `testSendEmailRunsOffTheCallingThread` asserts
> the property directly instead — it was confirmed to fail against the old code before being kept.
>
> Note for whoever touches the siblings: **`hc-admin` and `hc-professional` have the identical
> defect**, byte-for-byte. It is the generated JHipster reactive `MailService`, not a local edit.

> **Fixed 2026-08-02 — seeded credentials reached production.** From the first deploy until this fix,
> `https://patient.abofonsa.com` accepted `admin` / `Admin@01234`, along with `user`, `patient` and
> `angel` on the same derivation. The cause was structural rather than an oversight in one file: the
> account seeding lived in Mongock change units, and **a change unit has no notion of a Spring
> profile** — it runs wherever the application runs. The class carrying a "these are development
> credentials, rotate them" javadoc was therefore executing, unchanged, in production.
>
> The code fix moved account seeding out of the change units (which now seed authorities only, still
> in every profile, because registration depends on them) into `DevSeedDataInitializer`, gated to
> `dev`/`test`, and `AdminBootstrapInitializer`, which has no default password. The live database was
> remediated separately, since no code change can alter accounts that already exist: the
> administrator's password was rotated to a value generated on the server, and `user`, `patient` and
> `angel` were deleted. All four derived passwords now return 401 publicly.
>
> The lesson worth keeping: a comment saying a credential is for development does not make it so —
> only a profile gate, or the absence of a default, does.

- `[ ]` **Align the JWT signing key with `hc-patient-service`.** The `base64-secret` committed here differs from the microservice's in both `application-dev.yml` and `application-prod.yml`, so relayed tokens fail signature validation downstream. Source both from one env var / Consul KV entry and drop the committed values.
- `[x]` **Deleted `deploy.sh` and `build-deploy.sh` (2026-08-11).** Both were copies from the admin gateway: `deploy.sh` tagged and pushed `admingateway` to `docker-registry.jojoaddison.net/hc/`, and `build-deploy.sh` additionally ran `git pull -r`, created a `v$version` tag in THIS repo, and `cd`'d into a `br-admin-gateway` directory. Deleted rather than fixed, because `hc-patient/deploy` already owns deployment and a second script here could only ever drift from it.

  Prompted by `deploy.sh` finally being run by mistake, from this directory instead of `hc-patient/deploy`. It failed to find an `admingateway` image, pushed nothing, printed **`build and deploy completed.`** and exited 0 — a false success that read as a completed production deploy. That is the argument against leaving a wrong script in place with a warning in the docs: the warning is only read by someone who already suspects a problem.
- `[ ]` Delete the leftover `angular.json` — `skipClient: true`, there is no `src/main/webapp`, and it builds nothing.
- `[ ]` Delete the leftover `webpack/` directory (`environment.js`, `proxy.conf.js`, `webpack.custom.js`, `logo-jhipster.png`) for the same reason — it is tracked, and nothing in a `skipClient` app reads it. Worth noting before deleting: its `proxy.conf.js` already targeted **5505**, which is corroboration that 5505 was the gateway's intended port all along and 5503 was the drift.
- `[ ]` Wire CI. No workflows exist in `.github/`; `ci:backend:test` and `ci:server:await:patientgateway` are unused entry points. The dashboard repo publishes to GHCR — mirror or justify a different target.
- `[ ]` Decide the API-docs posture: OpenAPI is only served when the `api-docs` profile is active, and `/v3/api-docs/**` additionally requires `ROLE_ADMIN`.
- `[ ]` Confirm mail configuration per environment — account activation and password reset silently depend on a working `JavaMailSender` (`application-*.yml` `spring.mail` on port 25).

### Spring Boot 4 upgrade — finished, with leftovers

The Java 26 / Spring Boot 4 upgrade had left the project unbuildable; `./mvnw` could not even read the POM. **`./mvnw verify` is now green: 111 tests, 0 failures, 0 errors.** Fixed: renamed `spring-cloud-starter-gateway` → `spring-cloud-starter-gateway-server-webflux` and `spring-boot-starter-aop` → `spring-boot-starter-aspectj`, pinned the artifacts Spring Boot 4's BOM no longer manages (`spring-boot-loader-tools`, Dropwizard `metrics-core`), dropped the unpublished `jackson-datatype-jsr310`, added `spring-boot-webflux-test`, moved the Mongo properties to the `spring.mongodb.*` prefix (the old one is deprecated at _error_ level, so the configured URI was being ignored at runtime), and updated the moved/renamed types (`MongoAutoConfiguration`, Jackson 3 annotations and `JacksonException`, Hibernate Validator's `EmailValidator`, `@MockitoBean`, `@WebFluxTest`, `@AutoConfigureWebTestClient`). Two test-layer breakages were fixed alongside:

- `[x]` `TokenAuthenticationIT` / `TokenAuthenticationSecurityMetersIT` failed with `No qualifying bean of type ServerHttpSecurity`, because Spring Boot 4's `@WebFluxTest` slice no longer contributes reactive security to a context that imports `SecurityConfiguration`. `AuthenticationIntegrationTest` now adds `@ImportAutoConfiguration(ReactiveWebSecurityAutoConfiguration.class)` — note Boot 4 renamed that class from `ReactiveSecurityAutoConfiguration`.
- `[x]` `SpaWebFilterIT` got a 500 for `/v3/api-docs` because BlockHound tripped on `java.io.RandomAccessFile#readBytes`. Generating the document genuinely reads jar entries — it scans the classpath for webhook classes and springdoc's Kotlin customizers make kotlin-reflect load its built-ins — so `JHipsterBlockHoundIntegration` allows blocking within `AbstractOpenApiResource#getOpenApi`. The document is cached, so this is once per application rather than per request. If `/v3/api-docs` latency ever matters, warm it at startup instead of on the first request.

Deploying the stack for the first time (see `hc-patient/deploy`) surfaced two more, both fixed:

- `[x]` **Routing was completely inert.** Spring Cloud Gateway 5 moved every server property under
  `spring.cloud.gateway.server.webflux.*`, so the `discovery.locator` block and the `JWTRelay`
  default filter in `application.yml` bound to nothing. The discovery locator produced no routes at
  all: `/services/hcpatientservice/**` returned 404 for an authenticated caller, while unauthenticated
  callers still got a 401 from the security filter, which is what kept it hidden. With the keys moved,
  an authenticated request to `/services/hcpatientservice/api/profiles` returns 200 through Consul
  discovery.
- `[x]` **`GET /api/gateway/routes` always returned `[]`.** It called
  `routeLocator.getRoutes().subscribe(...)` and returned the still-empty list immediately, so the
  response was serialized before the asynchronous subscription filled it. Now returns a `Mono` and
  collects the routes; the blocking `DiscoveryClient` lookup moved to the bounded-elastic scheduler
  rather than running on an event-loop thread.

Still open:

- `[ ]` Upgrade to Testcontainers 2.x. The build pins 1.21.4 ahead of Spring Boot 4's BOM (2.0.5) because 2.x re-coordinated every module (`junit-jupiter` → `testcontainers-junit-jupiter`) and moved the container classes (`org.testcontainers.containers.KafkaContainer` → `org.testcontainers.kafka.KafkaContainer`).
- `[ ]` Revisit the Modernizer exclusion for `String.equalsIgnoreCase`. Modernizer was bumped 2.7.0 → 3.5.0 (2.7.0 cannot read Java 26 bytecode and silently failed the build), and it now suggests `String.equalsFoldCase`. That is not an equivalent swap for login/email comparison, so the rule is excluded in `pom.xml`.

## Phase B — auth/onboarding features

- `[~]` `PATIENT`/`ANGEL` authorities: the constants and Mongock seeds exist. Still to do — decide the authorization rules that use them, extend `AuthorityResource` expectations, and teach the JWT `auth` claim consumers in `hc-patient-service` and the dashboard about them (decision 1).
- `[ ]` Define role assignment at registration — `registerUser(...)` currently forces `ROLE_USER` regardless of the incoming DTO.
- `[ ]` Agree the onboarding contract with `hc-patient-service` (decision 2), including whether the gateway proxies a single onboarding call or the client makes two.
- `[ ]` Expose whatever profile linkage the clients need so a freshly registered user can be mapped to a `Profile` in the microservice.

## Phase C — test coverage backlog

Re-verified 2026-07-30: every item below is still open. "Add" means the file does not exist yet; "Expand" means it exists and needs cases. Follow existing conventions — `@IntegrationTest` for anything touching the Spring context, `WebTestClient` for reactive endpoints, Testcontainers-backed Mongo/Kafka, and no blocking calls (BlockHound via `config/JHipsterBlockHoundIntegration`).

### Authentication and security

1. `[ ]` **Add `security/jwt/JWTRelayGatewayFilterFactoryTest`.**
   - Requests with no `Authorization` header pass through unchanged.
   - `Bearer <token>` calls `ReactiveJwtDecoder.decode(...)` and forwards the request with bearer auth preserved.
   - Malformed headers (`Basic ...`, `Bearer `, too-short bearer values) fail with `IllegalArgumentException`.
   - Decoder failures propagate and do not call the downstream chain.
2. `[ ]` **Expand `web/rest/AuthenticateControllerIT`.**
   - Login by email, not only by username.
   - Non-activated users cannot authenticate.
   - Decode the returned JWT and assert `sub`, `auth`, and expiration claims.
   - `rememberMe=true` produces a longer expiry than the default token.
   - `GET /api/authenticate` with a valid JWT returns the principal name.
3. `[ ]` **Expand `security/DomainUserDetailsServiceIT`.**
   - Unknown login and unknown email both raise `UsernameNotFoundException`.
   - A user with authorities maps them into `UserDetails.getAuthorities()`.
4. `[ ]` **Expand `security/SecurityUtilsUnitTest`.**
   - `getCurrentUserLogin()` with a `Jwt` principal, and with no security context (empty result).
   - `getCurrentUserJWT()` when credentials are not a `String` (empty result).
   - No-context coverage for `hasCurrentUserAnyOfAuthorities(...)`, `hasCurrentUserNoneOfAuthorities(...)`, `hasCurrentUserThisAuthority(...)`.

### User service and account flows

5. `[ ]` **Expand `service/UserServiceIT` — `registerUser(...)`.**
   - Login and email persisted lowercase; password encoded; `activated=false`; `activationKey` generated.
   - Only `ROLE_USER` assigned even if the DTO asks for more.
   - Existing non-activated user with the same login (or email) is deleted and replaced.
   - Existing activated user with the same login raises `UsernameAlreadyUsedException`; same email raises `EmailAlreadyUsedException`.
   - Audit fields fall back to `Constants.SYSTEM` with no authenticated principal.
6. `[ ]` **Expand `service/UserServiceIT` — `createUser(...)`.**
   - `langKey == null` falls back to `Constants.DEFAULT_LANGUAGE`.
   - Admin-created users get an encoded random password, non-null `resetKey`/`resetDate`, `activated=true`.
   - Unknown authority names are ignored (only repository hits are added).
   - Login/email normalization still applies.
7. `[ ]` **Expand `service/UserServiceIT` — `updateUser(AdminUserDTO)`.**
   - Authorities are replaced, not merged.
   - Updated login/email persisted lowercase.
   - Unknown user id returns an empty result without mutating data.
8. `[ ]` **Expand `web/rest/AccountResourceIT`.**
   - `GET /api/account` for a principal whose user record no longer exists uses the controller error path.
   - `POST /api/account` where the current login is in the security context but not the repository uses the error path.
   - `POST /api/register` ignores client-supplied `activated`; persisted login/email are lowercase.
   - `POST /api/account/reset-password/init` for a non-activated user returns `200` without creating reset metadata.
   - `POST /api/account/reset-password/finish` with an expired reset key leaves the password unchanged.

### Admin and public user resources

9. `[ ]` **Expand `web/rest/UserResourceIT`.**
   - Non-admin access to every `/api/admin/users` endpoint is forbidden.
   - Disallowed sort fields on `GET /api/admin/users` return `400`.
   - `PUT /api/admin/users` with an unknown id returns `404`.
   - Cover `PUT /api/admin/users/{login}` and pin whether the path variable is ignored or must match the body login.
   - Create/update persist lowercase login/email.
   - Update replaces authorities rather than accumulating them.
10. `[ ]` **Expand `web/rest/PublicUserResourceIT`.**
    - Non-activated users are excluded from `GET /api/users`.
    - Invalid sort properties return `400`.
    - Pagination headers are present.
    - The response stays limited to `UserDTO` fields (`id`, `login`).

### Gateway and Kafka

11. `[ ]` **Add `web/rest/GatewayResourceIT`.**
    - `activeRoutes()` rewrites the route predicate into `RouteVM.path`.
    - The route id suffix becomes a lowercase `serviceId`.
    - Routes whose `serviceId` matches `spring.application.name` are excluded.
    - `DiscoveryClient.getInstances(serviceId)` results are attached to returned routes.
    - An empty route stream returns `200 OK` with an empty list.
12. `[ ]` **Expand `web/rest/PatientGatewayKafkaResourceIT`** — `consume()` emits multiple accepted messages in order, not just the first.
13. `[ ]` **Add `broker/KafkaConsumerTest`** — `accept(...)` pushes messages into the `Flux` from `getFlux()`, and sequential calls are observed in order.
14. `[ ]` **Add `broker/KafkaProducerTest`** — `get()` returns the current hard-coded payload `kakfa_producer`. (Note the typo in the payload; decide whether to fix it before pinning it in a test.)

### Mail and bootstrap

15. `[ ]` **Expand `service/MailServiceIT`** — `sendEmailFromTemplate(...)` with a `null` email never calls `JavaMailSender.send(...)`.
16. `[x]` **Migration and bootstrap tests** — done as unit tests rather than an IT, because the property under test is
    about which profile creates what, and a Mongo container adds nothing to that.
    - `MigrationsSeedNoAccountsTest`: 001 creates exactly `ROLE_USER`/`ROLE_ADMIN`, 002 exactly
      `ROLE_PATIENT`/`ROLE_ANGEL`/`ROLE_USER`, neither ever saves a `User`, and `DevSeedDataInitializer` carries a
      `@Profile` of exactly `dev`/`test`.
    - `AdminBootstrapInitializerTest`: no password (empty, blank or null) creates nothing; a configured password creates
      one activated admin with `ROLE_ADMIN`+`ROLE_USER` and the encoded value; an existing admin is never rewritten.
17. `[ ]` **Consider an IT for the account lifecycle against a real Mongo** — registration granting `ROLE_USER`, then
    login — which is the part these unit tests deliberately do not cover.

## Working agreement

- Reactive only: `Mono`/`Flux` return types, no blocking calls — BlockHound fails the build otherwise.
- Respect the ArchUnit layer boundaries in `TechnicalStructureTest`.
- Verify with `./mvnw verify`; single integration test via `./mvnw verify -Dit.test=XIT` (`-Dtest=` cannot match `*IT` classes since surefire excludes them).
- Keep `README.md`'s endpoint/security inventory in sync when routes or security rules change.
