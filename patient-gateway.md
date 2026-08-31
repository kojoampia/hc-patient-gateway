# Patient Gateway — Plan

Single plan of record for `hc-patient-gateway` (`patientGateway`). It consolidates the test-coverage backlog that used to live in the now-deleted `missing-test-cases.md` with the gateway-side slices of the Health Connect patient blueprint/checklist and the repo hygiene items found while auditing this service.

- **Baseline verified:** 2026-08-03 against `pom.xml`, `.yo-rc.json`, `src/main/java`, `src/test/java`, `src/main/resources/config`. (Previous baseline 2026-07-30.)
- **Companion docs:** `CLAUDE.md` (what exists and how it is wired), `AGENTS.md` (standing expectations), `README.md` (endpoint inventory, security rules, seed data).
- **Sibling plans:** `hc-patient-service/patient-api.md`, `hc-patient-dashboard/patient-web.md`.

Status legend: `[x]` done · `[~]` partial / diverges from plan · `[ ]` not started.

## What changed since the last baseline

### Registrations record where the family came from (2026-08-25)

`web.abofonsa.com` links families to `/account/register?src=web-home` from its landing page, and the dashboard
now forwards that on the registration payload. It landed nowhere until this: the attribution existed on the
wire and in no record. See `docs/patient-handoff-contract.md`.

- **`User.source`**, written once at registration and never again. It records a fact about the past, not a
  property of the account, which is why it is on `ManagedUserVM` and **not** on `AdminUserDTO` — putting it
  there would let an administrator rewrite where somebody came from through the user-management API.
- **`HandoffSource` is the allowlist, and the one in the browser is not.** `POST /api/register` is public and
  unauthenticated, so the parameter is whatever any stranger cares to send, and it lands on a record a human
  reads and a report counts. The dashboard's copy keeps an ordinary visitor's URL honest; this is the control.
- **An unrecognised source does not cost somebody an account.** Registration succeeds and the value is simply
  not recorded — it is not the caller's problem that we do not know their surface.
- `registerUser` takes it as a parameter rather than reading it off the DTO, so the only way to set it is to
  have passed it through the allowlist first. A field on the DTO could be forwarded from anywhere by accident.

**The cost, stated because it is silent:** a surface nobody has added to `KNOWN` loses its attribution with no
error and nothing to notice. The sending contract says that side may add surfaces without telling us, so this
will happen; the response appended to that document tells them.

### Something now notices when outbound mail breaks (2026-08-25)

Outbound mail was refused by the relay from an unknown date until 2026-08-07, and it was noticed **only
because somebody looked at `/management/health`**. Spring's health indicators fail quietly: no log line, no
metric, no alert, every other check green — while account activation and password reset failed for real users
with no error anybody could see.

- **`MailHealthMetrics`** tests the relay every five minutes and exports `hc_patient_mail_up` alongside
  `hc_patient_mail_checked_timestamp`. Two gauges rather than one, because a gauge that stops updating
  otherwise reads as a permanent last value rather than as a check that died.
- It calls `testConnection()` rather than reading the health indicator — the indicator does the same thing
  internally, and reaching it through Actuator's registry means depending on the shape of that registry in a
  _reactive_ application, which differs from the servlet case and has moved between Boot versions.
- The sender is **optional**. Spring only creates it when `spring.mail.host` is set, and a hard dependency
  would turn "SMTP is not configured on my laptop" into "the gateway will not start". Absent, nothing is
  registered — so the metric is missing rather than a confident zero, because an unconfigured relay and a
  refusing relay are different facts.
- Failures log at WARN on **every** check, not only on transition. The whole defect was a failure that left no
  trace.

**What it still does not prove is delivery.** `up=1` means the relay accepted our credentials, which was
equally true on 2026-08-02 before they rotted. Mail that authenticates and is then dropped downstream reports
healthy. Alert rules are staged in `hc-patient-ci`; the canary remains the stronger answer.

### `ROLE_PROFESSIONAL` removed; the eight disciplines issued instead (2026-08-24)

The entry below is now history. `ROLE_PROFESSIONAL` was a blanket clinical authority that **only this
gateway ever minted and only `hc-patient-service` ever checked** — a name this subsystem invented for
itself and then required of everybody else. Because the three stacks share one JWT signing key, a
clinician signing in to `hc-professional` reached the patient service holding `ROLE_DOCTOR`, matched no
check, resolved to no patient, and was served empty lists rather than a refusal. Two halves of one
platform had two names for a clinician, and only one of them was issued by the portal clinicians use.

- **`AuthoritiesConstants`** drops `PROFESSIONAL` and gains the eight disciplines, spelled
  byte-identically to `hc-professional`'s own constants. There is no shared artefact between the
  repositories to enforce that; the api's `AuthoritiesConstantsUnitTest` is the closest thing.
- **`ClinicalDisciplineRolesMigration`** (change unit `004`) seeds the eight, **replaces**
  `ROLE_PROFESSIONAL` with `ROLE_DOCTOR` on every account holding it, then deletes the authority.
  Replacing rather than stripping is deliberate: doctor is the discipline whose reach matches what the
  blanket role granted, so nobody's capability changes on the day. Stripping without replacing would
  have been the same outage from the other direction — an account that signs in exactly as before and
  sees nothing.
- **Why `003` was kept rather than deleted.** Mongock records a change unit as executed and never runs
  it again, so deleting it would have removed nothing from any existing database: production applied it
  on 2026-08-11 and the authority would have stayed there, on accounts and in tokens, while the patient
  service quietly stopped honouring it. Removal has to be its own forward step.
- **It finds holders by reading users, not by querying `authorities.name`.** `Authority`'s name is its
  `@Id`, so the embedded subdocument's field name is a detail of how Spring Data flattens it — a query
  that guessed wrong would match nothing, report zero holders, and look exactly like a clean run.
- **In production it touches no account.** The authority was seeded there and granted to nobody, so the
  net effect is eight authority documents an administrator may assign. Verify it the way `003` was
  verified: the applied line in the gateway log, which names the count.
- **`doctor` now holds `ROLE_DOCTOR`**, in `DevSeedDataInitializer` and in `seed-document-fixture.json`.
  Note that seeder only creates users that are _missing_, so an existing development database is
  migrated by `004` rather than by the seed change.

### `ROLE_PROFESSIONAL` can now be issued, and `doctor` holds it (2026-08-11)

The patient service has gated cross-patient reads and its staff-only writes on `ROLE_PROFESSIONAL`
since the 2026-08-05 audit (`PatientScope.isUnrestricted`), but nothing could issue it — the
authority named an access level no token could carry, so a clinician signing in was scoped to their
own records like any patient. The professional-dashboard demo dataset made that concrete: its one
clinician is keyed by `accountLogin: "doctor"`, and a `doctor` with only `ROLE_USER` resolves to no
`Profile` and therefore sees nothing at all.

- **`AuthoritiesConstants.PROFESSIONAL`** added, and **`ProfessionalRoleMigration`** (change unit
  `003`) seeds the authority in every profile. A separate change unit from `002` for the same reason
  `002` is separate from `001`: Mongock never re-runs one, so a database where `002` has already run
  would otherwise never receive the role.
- **`DevSeedDataInitializer` seeds `doctor`** (`user-5`, `ROLE_PROFESSIONAL` + `ROLE_USER`) under
  `dev` and `test` only, with the same derived password scheme as the other four. It is the only
  account anywhere that holds the role; nothing grants it in production, where it is an
  administrator's to assign. Registration hardcodes `ROLE_USER` (`UserService.registerUser`), so it
  cannot be self-granted.
- **The join to the patient service is email.** That service runs with `skipUserManagement` and has
  no `User` document, so `doctor@localhost` is the only identifier the two share; its
  `DemoDataInitializer` writes the matching `Professional` with that email. Changing either login or
  email breaks the join silently — see `hc-patient-service/patient-api.md`.
- **Verified:** `./mvnw verify` — 134 unit tests, 48 integration tests.

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

1. ~~**Patient/angel roles.**~~ Settled 2026-08-19, and the answer is the interesting part: **neither role authorizes anything.** Registration grants `ROLE_USER` + `ROLE_PATIENT`; a nominated care angel's account gets `ROLE_ANGEL`; the dashboard's `Authority` enum now knows all four. But a patient's access to their own record comes from `hc-patient-service` resolving their email to a `Profile`, and an angel's comes from an `ACTIVE` `CareDelegation` that service re-reads on every request. The roles are for menus and for telling people apart. Authorizing on `ROLE_ANGEL` would have meant a revoked angel keeping access until their token expired — days, with `rememberMe`.
2. ~~**Registration ownership.**~~ Settled 2026-08-19: **the patient service owns onboarding; this service owns accounts, and nothing owns a transaction** because there is none to own. Registration stays exactly as it was. The client calls `POST /api/register` here, then the onboarding endpoints there, and each step is independently meaningful so a failure part-way leaves a resumable state rather than a corrupt one — which is what makes the missing transaction affordable on standalone Mongo. This service gained one onboarding-adjacent endpoint, `POST /api/care-angels`, only because creating a user is something no other service can do.
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

- `[x]` ~~**Align the JWT signing key with `hc-patient-service`.**~~ **Done 2026-08-05**, recorded here 2026-08-21
  after the claim was found still standing in this file, in `patient-api.md`, in `docs/CLAUDE.md` and in
  `mobile/patient-mobile.md`. `application-dev.yml` carries the SAME committed key as the microservice — public by
  construction and labelled as such, so `./mvnw` works with no setup in either repo — and `application-prod.yml`
  carries `${JWT_BASE64_SECRET:}` with **no default**, so this gateway refuses to start rather than mint tokens the
  microservice cannot validate. `.yo-rc.json` holds an empty `jwtSecretKey` in both repos.

  Verified by comparing values rather than comments, across dev, prod, `.yo-rc.json` and both test configs.

  **The residual risk is a partial injection** — this gateway handed a
  `JHIPSTER_SECURITY_AUTHENTICATION_JWT_BASE64_SECRET` that the microservice was not, or the reverse. Every compose
  file in the workspace injects one variable into both services, so it would take a hand edit; but it presents
  exactly like the old defect, which is why the alert rule in `deploy/prod-server/observability/` names it first.

- `[x]` **Deleted `deploy.sh` and `build-deploy.sh` (2026-08-11).** Both were copies from the admin gateway: `deploy.sh` tagged and pushed `admingateway` to `docker-registry.jojoaddison.net/hc/`, and `build-deploy.sh` additionally ran `git pull -r`, created a `v$version` tag in THIS repo, and `cd`'d into a `br-admin-gateway` directory. Deleted rather than fixed, because `hc-patient/deploy` already owns deployment and a second script here could only ever drift from it.

  Prompted by `deploy.sh` finally being run by mistake, from this directory instead of `hc-patient/deploy`. It failed to find an `admingateway` image, pushed nothing, printed **`build and deploy completed.`** and exited 0 — a false success that read as a completed production deploy. That is the argument against leaving a wrong script in place with a warning in the docs: the warning is only read by someone who already suspects a problem.

- `[x]` **Deleted the leftover `angular.json` and `webpack/` — 2026-08-30.** `skipClient: true`, there is no `src/main/webapp`, and neither built anything. Five files: `angular.json`, `webpack/environment.js`, `webpack/proxy.conf.js`, `webpack/webpack.custom.js`, `webpack/logo-jhipster.png`. Checked before deleting that nothing reads them — `package.json` has no `ng` or webpack script, `pom.xml` names neither, and the only reference to `webpack/` anywhere was `angular.json`'s own pointer at `webpack.custom.js`, so the two went together or not at all.

  **The one thing worth keeping out of `webpack/proxy.conf.js` is already written down**, which is what made deleting it safe: it targeted **5505**, corroborating that 5505 was the gateway's intended port all along and 5503 was the drift. That is recorded at line 117 of this file and in `CLAUDE.md`, so the evidence outlives the file that carried it. A leftover is only safe to delete once the thing it accidentally proved has been written somewhere that is not a leftover.

  **`jest.conf.js` went with them, and finding it is the reason to grep rather than trust "inert".** It did `require('./webpack/environment')` at line 6 — the one live reader of a directory four documents describe as read by nothing. It is dead by every other measure: there is no `jest` dependency in `package.json`, no `test` script, and its `testMatch` points at `src/main/webapp/app/**`, which does not exist in a `skipClient` app. So the require was dangling in effect long before this deletion made it dangling in fact, and nothing would have failed either way — but only because the file it broke was itself never run. `.eslintignore` lost its now-pointless `webpack/` line at the same time.

  `[ ]` **One sibling survives: `tsconfig.spec.json`**, the last of this generator's client scaffolding. It is left because `tsconfig.json` names it in `references`, so removing it means editing a file that is not itself a leftover — a smaller decision than it looks, but a different one, and not worth folding into a cleanup silently.

- `[x]` **CI is wired and has been since 2026-08-05** (`332c69a`) — corrected 2026-08-31. `build.yml` runs `./mvnw verify` and a dependency scan on every push and pull request; `release.yml` publishes to GHCR on push to main, mirroring the dashboard, which is what this entry asked for. `.github/` has not been empty for four weeks.

  Still true: `ci:backend:test` and `ci:server:await:patientgateway` are unused entry points, because the workflow calls `./mvnw` directly. `[ ]` Wire them up or delete them.

- `[x]` **`api-docs` posture decided: both gates stay — 2026-08-31.** Settled together with `hc-patient-service`, whose `patient-api.md` carries the full reasoning; a decision made in one repo and not the other is how the two come apart.

  In short: `springdoc.api-docs.enabled: false` under the `!api-docs` profile decides whether the schema _exists_, and the `ROLE_ADMIN` rule on `/v3/api-docs/**` decides who may read it when it does. Not redundant — turning the profile on publishes nothing to the world, which is what makes the opt-in cheap rather than a lock people route around.

  This repo has one gate the service does not: `/services/*/v3/api-docs` is separately `ROLE_ADMIN`, so the gateway does not become a way to read a downstream schema that the downstream service is itself protecting.

- `[x]` **Mail confirmed per environment — 2026-08-31.** Read out of the four config files and `hc-patient-ci`'s compose rather than assumed:

  | Where                            | `spring.mail`                                            | Health indicator                            | Sends?                                                                                               |
  | -------------------------------- | -------------------------------------------------------- | ------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
  | `application.yml` (all profiles) | —                                                        | **`management.health.mail.enabled: false`** | —                                                                                                    |
  | `dev`                            | `localhost:25`, no credentials                           | off                                         | No. Nothing listens; sends fail locally and that is intended                                         |
  | `test`                           | `localhost`                                              | off                                         | No                                                                                                   |
  | `quality`                        | unset                                                    | explicitly `false` in its compose           | **No, and it does not pretend to**                                                                   |
  | `prod`                           | `localhost:25` committed, **overridden on every deploy** | `true`, set by the compose                  | Yes — `smtp-relay.gmail.com:587`, STARTTLS + auth, from the estate `~/webroot/01-healthconnect/.env` |

  **Production is correct only because the deploy injects four variables.** `SPRING_MAIL_{HOST,PORT,USERNAME,PASSWORD}` come from `SMTP_MAIL_*` in the estate file; the committed `localhost:25` is never what runs. That is a reasonable arrangement — credentials do not belong in a repository — but it means _this repository cannot be read to find out whether mail works_, which is worth knowing before trusting a config file here.

  Two things found while confirming, and one is now fixed:

  `[x]` **`jhipster.mail.base-url` in `application-prod.yml` was still the generator's placeholder**, `http://my-server-url-to-change`. Every activation and password-reset link is built from it. It never mattered because the compose overrides it with `JHIPSTER_MAIL_BASE_URL`, but the failure mode if injection ever stopped is the quiet kind: the mail sends, and the link is dead. Set to `https://patient.abofonsa.com`, so the un-injected case is correct instead of broken.

  `[~]` **The health indicator is off in the repository and on only in production's compose**, which is the reverse of the usual arrangement and worth stating rather than reversing: `mail: UP` proves Spring connected, negotiated STARTTLS and authenticated — nothing more. It read `UP` on 2026-08-02 while the credential had silently rotted. See `docs/open-issues.md` §2: **no message is known to have ever left this stack**, and that is the item that closes this one properly.

### Deletion mails, and the one consumer that carries them (2026-08-31)

- `[x]` **A patient is told when their deletion request moves.** Until now they were told nothing — they raised the one irreversible request in the product and learnt the outcome by signing back in, if they thought to. After a completed erasure they could not even do that, because there is no record left to show them: **the mail is the only proof they get.**

  `DeletionRequestMailer` consumes `DeletionRequestChanged` off `patient-events` and sends on all four transitions: requested (with the date in writing), withdrawn, completed, refused. `MailService` gained the four methods, four Thymeleaf templates, and fifteen message keys across all four bundles.

  The refusal mail does **not** carry the administrator's reason. That text is unbounded and this address is outside the product; the patient reads it in the portal, authenticated. The completed mail deliberately links nowhere into the app — by then there is nothing to show, and sending somebody to an empty screen is a worse answer than none.

- `[x]` **The `patientEventsConsumer` binding moved to `PatientEventMailRouter`.** There can be exactly one function on it — `spring.cloud.function.definition` names it and `patientEventsConsumer-in-0` points it at the topic — so a second family of mail could not bring a second consumer. The router is that seam: adding a third is a line there rather than reopening whichever mailer owned the binding first.

  **The bean name comes from the method, never the class**, so the move is invisible to the binding. That was safe by construction and is now safe by test.

- `[x]` **An erased record's login is closed — decided and built 2026-08-31.** `DeletionAccountCloser` consumes the same `COMPLETED` event and **deactivates** the `User`. Until now a completed erasure left somebody able to sign in, resolving to no patient and seeing an empty portal: correct behaviour, and not the same thing as being gone.

  **Deactivated rather than deleted, and the cost is stated rather than glossed.** Deleting is what the patient literally asked for and would remove their email address too. Deactivating keeps the audit trail whole — the retained `DeletionRequest` names a login, and a login resolving to nothing is a weaker record of what was done than one resolving to a closed account. The price is that **this retains an email**, the one piece of personal data erased everywhere else in the flow. `DeletionAccountCloserUnitTest` pins the choice as a choice so that changing it is a decision rather than an edit — and note it changes two published documents with it: the privacy policy and the Play data-safety declaration both describe what erasure removes.

  Only `COMPLETED` closes anything. Closing on a withdrawal would lock somebody out of a record they had just decided to keep, which is tested.

  One thing the decision quietly bought: **ordering stopped mattering.** A delete would have had to run strictly after the mailer, since the mail resolves its recipient by looking the account up — close it first and there is nobody left to tell. A deactivated row is still there to find. The router still calls mail first, and says why in a comment, because it would matter again the day somebody revisits this.

- `[x]` **`PatientEventConsumerBindingIT`** — new, and the point is the failure it catches. Rename the bound method, or move it without keeping its name, and Spring Cloud Stream has no function to bind: the context starts, the gateway serves every request as before, and **every mail in the product stops with nothing failing anywhere.** The producer side has had this cover since it was written; the consumer side had none. Four assertions — the bean exists under the name the YAML expects, it is bound to `patient-events`, it has a consumer group (without one each replica is a duplicate notifier), and both mailers are reachable from it. 4 tests, 802s.

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

- `[ ]` **Upgrade to Testcontainers 2.x** — still open, and scoped 2026-08-31 so the next attempt starts from the real shape rather than the pom's summary of it. The build pins 1.21.4 ahead of Spring Boot 4's BOM (2.0.5).

  **The code change is small: three files here and three in `hc-patient-service`**, four imports between them — `containers.KafkaContainer`, `containers.MongoDBContainer`, `containers.output.Slf4jLogConsumer`, `utility.DockerImageName` — plus the pom's artifact renames (`junit-jupiter` → `testcontainers-junit-jupiter`).

  **What the pom's comment does not say is the part that makes it more than an import sweep.** 2.x's `org.testcontainers.kafka.KafkaContainer` is built for the `apache/kafka` image; these tests run `confluentinc/cp-kafka` (7.6.0 here, 7.5.2 in the api). So the upgrade is also an image change, and an image change under a broker is a behavioural change rather than a rename — it wants a full `verify` in both repos to believe, and those are Testcontainers runs measured in tens of minutes, not seconds.

  Two constraints on doing it: **both repos move together** — the pin's stated reason is that 1.21.4 "matches hc-patient-service", and a divergence there is worse than being a version behind — and there is no functional gain, so it belongs in a session with time to run both suites rather than at the end of one.

  Worth noting the alignment that argues for eventually doing it: the _quality_ stack already runs `apache/kafka:3.9.0`, so the tests and the deployed broker currently disagree about which Kafka they exercise.

- `[x]` **The Modernizer exclusion is right, and stays — reviewed 2026-08-31.** The rule suggests `String.equalsFoldCase`, and full Unicode case folding is **not** equivalent to `equalsIgnoreCase`: it also matches `"Fuß"` against `"FUSS"`. These call sites compare logins and email addresses, where two distinct identities collapsing into one is an authentication defect rather than a style question.

  The exclusion already carried that reasoning in `pom.xml`; what was missing was a decision, so this is it. Note the shape of the finding — **a linter recommending a subtly different method is the kind of suggestion that is right in general and wrong here**, and the exclusion is worth more than the one-line fix it prevents.

## Phase B — auth/onboarding features

**Built 2026-08-19.** `docs/onboarding.md` is the plan of record; §16 is the contract.

- `[x]` `PATIENT`/`ANGEL` authorities — assigned, and consumed by the dashboard's `Authority` enum. The authorization rules turned out to be that there are none: see decision 1.
- `[x]` Role assignment at registration — `ROLE_USER` + `ROLE_PATIENT`. Two notes for whoever touches this next. The `ManagedUserVM`'s own `authorities` are still ignored, deliberately: registration decides, not the registrant, and `testRegisterAdminIsIgnored` pins it. And resolving the two authorities with `flatMap` into a shared `HashSet` is a race that passes alone and fails in a full class run — it is `concatMap` + `collect` for that reason, in both places that do it.
- `[x]` The onboarding contract — agreed and built; the client makes two calls (decision 2).
- `[x]` Profile linkage — none was needed. The JWT's `email` claim was already the join, and onboarding mints the `patientId` on the patient-service side.
- `[x]` **`POST /api/care-angels`** — finds or creates the account a nominated angel signs in with. An email that already has an account gets `ROLE_ANGEL` rather than a second account. A new one is created _already activated_ with a random UUID password nobody knows, and invited by the ordinary password-reset mail: that satisfies "cannot authenticate until they set a password" by construction, changes no endpoint contract, and leaves the web activation screen alone. Login derives as `Grace Mensah` -> `ge_mensah`, with a numeric suffix on collision, accents transliterated (`LOGIN_REGEX` would refuse them) and a fallback to the email's local part.
- `[x]` **Account events and the delegation mails.** `AccountCreated` and `AccountActivated` publish to `patient-events`; `CareDelegationChanged` is consumed off the same topic to send the mails, because only this service can send mail and only the patient service knows when a delegation changed. An angel stepping down mails the patient — they are left with nobody able to act for them and only they can nominate a replacement; a patient revoking is not told what they just did.
- `[x]` **`/api/plans`** proxied to Abofonsa's content API, a deliberate exception to discovery-based routing.

Two traps worth knowing before adding anything here:

- **`src/test/resources/config/application.yml` is the same classpath resource as the main one** and replaces it wholesale rather than merging. Anything configured only in main is configured for production and for nothing any test can see — this caused three separate defects with three different symptoms and a green suite each time. Mirror every configuration change into both.
- **BlockHound is not decoration.** It caught the event publisher building its envelope on a Netty event loop, because `UUID.randomUUID()` draws on `SecureRandom` and can block. The whole publish runs on `boundedElastic`, not just the send — the same lesson `MailService` cost a production incident to learn.

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
