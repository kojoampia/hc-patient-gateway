# Patient Gateway — Plan

Single plan of record for `hc-patient-gateway` (`patientGateway`). It consolidates the test-coverage backlog that used to live in the now-deleted `missing-test-cases.md` with the gateway-side slices of the Health Connect patient blueprint/checklist and the repo hygiene items found while auditing this service.

- **Baseline verified:** 2026-07-30 against `pom.xml`, `.yo-rc.json`, `src/main/java`, `src/test/java`, `src/main/resources/config`.
- **Companion docs:** `CLAUDE.md` (what exists and how it is wired), `AGENTS.md` (standing expectations), `README.md` (endpoint inventory, security rules, seed data).
- **Sibling plans:** `hc-patient-service/patient-api.md`, `hc-patient-dashboard/patient-web.md`.

Status legend: `[x]` done · `[~]` partial / diverges from plan · `[ ]` not started.

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
- `[x]` Mongock seeding, split into two idempotent change units sharing the helpers in `SeedData`: `InitialSetupMigration` (001) creates `ROLE_USER`/`ROLE_ADMIN` and the activated `user`/`admin` accounts, `PatientRolesMigration` (002) creates `ROLE_PATIENT`/`ROLE_ANGEL` and the `patient`/`angel` accounts. A second change unit rather than an edit to 001, so databases that already ran 001 still get the new roles. Seed passwords are derived per login, encoded with the application `PasswordEncoder`, and never logged — they are development credentials to rotate or remove outside development.
- `[x]` Spring Boot 4.0.6 / Java 26 upgrade, reactive throughout, BlockHound active in tests.

## Phase A — platform hygiene

- `[ ]` **Align the JWT signing key with `hc-patient-service`.** The `base64-secret` committed here differs from the microservice's in both `application-dev.yml` and `application-prod.yml`, so relayed tokens fail signature validation downstream. Source both from one env var / Consul KV entry and drop the committed values.
- `[ ]` Fix or delete `deploy.sh` and `build-deploy.sh`: both were copied from the admin gateway and still tag/push `admingateway` and expect a `br-admin-gateway` directory.
- `[ ]` Delete the leftover `angular.json` — `skipClient: true`, there is no `src/main/webapp`, and it builds nothing.
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
16. `[ ]` **Add `config/dbmigrations/InitialSetupMigrationIT`.**
    - `changeSet()` creates `ROLE_USER` and `ROLE_ADMIN`.
    - Seeded `user` is activated with only `ROLE_USER`.
    - Seeded `admin` is activated with both `ROLE_ADMIN` and `ROLE_USER`.
    - Both seeded users have `createdBy = Constants.SYSTEM`.

## Working agreement

- Reactive only: `Mono`/`Flux` return types, no blocking calls — BlockHound fails the build otherwise.
- Respect the ArchUnit layer boundaries in `TechnicalStructureTest`.
- Verify with `./mvnw verify`; single integration test via `./mvnw verify -Dit.test=XIT` (`-Dtest=` cannot match `*IT` classes since surefire excludes them).
- Keep `README.md`'s endpoint/security inventory in sync when routes or security rules change.
