# PatientGateway

Reactive Spring Cloud Gateway for user authentication, user administration, service routing, and a small Kafka bridge. It fronts the Health Connect patient microservice (`hc-patient-service`) and owns all user/account data for the patient subsystem.

## Stack

Values below come from `pom.xml` and `.yo-rc.json`:

| Component        | Version / choice                                                                   |
| ---------------- | ---------------------------------------------------------------------------------- |
| Java             | 26 (`java.version`); Maven Enforcer allows 17–26 (`[17,27)`), Maven ≥ 3.2.5        |
| Framework        | Spring Boot 4.0.6, Spring Cloud 2025.1.1, `tech.jhipster:jhipster-framework` 9.0.0 |
| Web stack        | Spring WebFlux + Spring Cloud Gateway (**reactive throughout**)                    |
| Security         | Spring Security, JWT resource server                                               |
| Data             | MongoDB, Mongock migrations                                                        |
| Messaging        | Kafka via Spring Cloud Stream (`confluentinc/cp-kafka:7.5.2` locally)              |
| Discovery/config | Consul                                                                             |
| Generator        | JHipster 8.3.0 (`skipClient: true`)                                                |

There is no full JHipster BOM here — only the `jhipster-framework` library — because the app was upgraded to Spring Boot 4 ahead of the generator.

## What is implemented

- **JWT authentication** via `/api/authenticate`
- **Self-service account flows** for registration, activation, profile updates, password changes, and password reset
- **Admin user management** via `/api/admin/users`
- **Authority management** via `/api/authorities`
- **Public user listing** via `/api/users`
- **Gateway route inspection** via `/api/gateway/routes`
- **Service proxying** through `/services/{serviceId}/**`
- **Kafka publish/consume endpoints** via `/api/patient-gateway-kafka`
- **Mongo bootstrap data** created by Mongock on startup

There is **no generated frontend app** in this repository (`skipClient: true`, no `src/main/webapp`). The main resources under `src/main/resources` are configuration, i18n bundles, and mail templates. The leftover `angular.json` at the repo root is inert. The patient UI lives in the separate `hc-patient-dashboard` repo and reaches this gateway over HTTP.

## Runtime defaults

- **Application port:** `5503`
- **Spring application name:** `patientGateway`
- **Consul:** `http://localhost:8500`
- **MongoDB:** `mongodb://localhost:27017/patientGateway`
- **Kafka broker:** `localhost:9092`
- **API docs:** enabled when the `api-docs` profile is active
- **Management base path:** `/management`

The app is configured to use **Consul for discovery/config** and will not start cleanly in normal operation if Consul is unavailable. In prod bootstrap config, Consul config is `fail-fast: true`.

## Routing and security behavior

- Spring Cloud Gateway discovery locator is enabled.
- Discovered services are exposed as:
  - `/services/{serviceId}/**`
- Gateway routing rewrites proxied paths to:
  - `/{remaining}`
- The default gateway filter is **`JWTRelay`**, which forwards validated bearer tokens to downstream services.

Downstream services must therefore validate the same signing key. The `base64-secret` committed in this repo's `application-dev.yml`/`application-prod.yml` **differs** from the one committed in `hc-patient-service`, so both apps must be pointed at a shared secret (env var or Consul KV) before a relayed token is accepted downstream.

Security rules in `SecurityConfiguration` currently allow anonymous access to:

- `/api/authenticate`
- `/api/register`
- `/api/activate`
- `/api/account/reset-password/init`
- `/api/account/reset-password/finish`
- `/management/health`
- `/management/health/**`
- `/management/info`
- `/management/prometheus`
- `/services/*/management/health/readiness`

Admin authority is required for:

- `/api/admin/**`
- `/v3/api-docs/**`
- `/services/*/v3/api-docs`
- `/management/**` except the public endpoints above

All other `/api/**` and `/services/**` routes require authentication.

## Seed data

On an empty MongoDB database, `InitialSetupMigration` creates:

- authorities: `ROLE_USER`, `ROLE_ADMIN`
- users:
  - `admin` with `ROLE_ADMIN` and `ROLE_USER`
  - `user` with `ROLE_USER`

Both seeded users are activated and created by `system`.

## Main HTTP endpoints

### Authentication and account

- `POST /api/authenticate`
- `GET /api/authenticate`
- `POST /api/register`
- `GET /api/activate?key=...`
- `GET /api/account`
- `POST /api/account`
- `POST /api/account/change-password`
- `POST /api/account/reset-password/init`
- `POST /api/account/reset-password/finish`

### User and authority administration

- `GET /api/users`
- `POST /api/authorities`
- `GET /api/authorities`
- `GET /api/authorities/{id}`
- `DELETE /api/authorities/{id}`
- `POST /api/admin/users`
- `PUT /api/admin/users`
- `PUT /api/admin/users/{login}`
- `GET /api/admin/users`
- `GET /api/admin/users/{login}`
- `DELETE /api/admin/users/{login}`

### Gateway and Kafka

- `GET /api/gateway/routes`
- `POST /api/patient-gateway-kafka/publish?message=...`
- `GET /api/patient-gateway-kafka/consume`

## Local development

Start the app with the default profile setup:

```bash
./mvnw
```

Equivalent npm script:

```bash
npm run app:start
```

The default dev setup expects local Consul, MongoDB, and Kafka.

### Start dependencies with Docker

Start everything required by the gateway:

```bash
npm run services:up
```

Or start services individually:

```bash
npm run docker:consul:up
npm run docker:db:up
npm run docker:kafka:up
```

Compose files live in `src/main/docker/`.

## Build and packaging

Build a production jar:

```bash
./mvnw -Pprod clean verify
```

Build a production war:

```bash
./mvnw -Pprod,war clean verify
```

Useful npm wrappers:

```bash
npm run java:jar:prod
npm run java:war:prod
```

## Docker image and containerized app

Build the application image:

```bash
npm run java:docker
```

Start the containerized app with its dependencies:

```bash
docker compose -f src/main/docker/app.yml up -d
```

The app container runs with:

- `SPRING_PROFILES_ACTIVE=prod,api-docs`
- MongoDB wired to `mongodb:27017`
- Consul wired to `consul:8500`
- Kafka wired to `kafka:9092`

## Testing

Run the full Maven verification:

```bash
./mvnw verify
```

Or use the existing npm wrapper:

```bash
npm run backend:unit:test
```

Run a single class or method. Integration tests (`*IT`, `*IntTest`) are owned by failsafe and selected with `-Dit.test`; surefire is configured to exclude them, so `-Dtest=SomethingIT` matches nothing:

```bash
./mvnw test -Dtest=SecurityUtilsUnitTest                  # unit test
./mvnw verify -Dit.test=AuthenticateControllerIT          # one integration test
./mvnw verify -Dit.test=AuthenticateControllerIT#testAuthorize
./mvnw verify -DskipITs                                   # unit tests only
```

Integration tests bring up MongoDB and Kafka with Testcontainers (see `IntegrationTest` and `config/*TestContainer*`), so they need Docker but not `npm run services:up`. BlockHound (`config/JHipsterBlockHoundIntegration`) fails tests that block on a reactive thread — keep controllers and services non-blocking.

Known coverage gaps are tracked in [`patient-gateway.md`](patient-gateway.md) under "Phase C — test coverage backlog".

Additional checks:

```bash
npm run backend:nohttp:test
npm run backend:doc:test
npm run prettier:check
```

## API docs and observability

When the `api-docs` profile is active:

- OpenAPI docs are available under `/v3/api-docs`
- Swagger UI assets are enabled by the springdoc dependency

Management endpoints are served under `/management`, including:

- `/management/health`
- `/management/info`
- `/management/prometheus`
- `/management/gateway`

## Optional supporting tools

The repository also contains Docker definitions for:

- JHipster Control Center: `src/main/docker/jhipster-control-center.yml`
- SonarQube: `src/main/docker/sonar.yml`
- Zipkin: `src/main/docker/zipkin.yml`
- Monitoring stack: `src/main/docker/monitoring.yml`

## Repository caveats

- `deploy.sh` and `build-deploy.sh` were copied from the admin gateway and still tag/push `admingateway` / expect a `br-admin-gateway` directory. They are **wrong for this repo** — fix or ignore them; do not run them expecting a patient-gateway image.
- `angular.json` is a leftover from before `skipClient` and builds nothing.
- `patient-gw.log` is output from the workspace-level `start-patient.sh` helper.
- No CI workflows exist in `.github/`; the `ci:*` npm scripts are unused entry points.

## References

- JHipster 8.3.0 docs: <https://www.jhipster.tech/documentation-archive/v8.3.0>
- Spring Cloud Gateway docs: <https://docs.spring.io/spring-cloud-gateway/reference/>
- Sibling repos: `hc-patient-service` (microservice), `hc-patient-dashboard` (Angular UI)
