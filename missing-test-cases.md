# Missing test cases

This list covers core backend logic that is present in `src/main/java` but is not currently exercised, or is only partially exercised, by the existing test suite.

## Authentication and security

1. **Add `src/test/java/net/jojoaddison/security/jwt/JWTRelayGatewayFilterFactoryTest.java`.**

   - Verify requests with no `Authorization` header pass through unchanged.
   - Verify `Bearer <token>` calls `ReactiveJwtDecoder.decode(...)` and forwards the request with bearer auth preserved.
   - Verify malformed headers such as `Basic ...`, `Bearer `, or too-short bearer values fail with `IllegalArgumentException`.
   - Verify decoder failures propagate and do not call the downstream chain.

2. **Expand `src/test/java/net/jojoaddison/web/rest/AuthenticateControllerIT.java`.**

   - Add login-by-email coverage, not only login-by-username.
   - Add a test proving non-activated users cannot authenticate.
   - Decode the returned JWT and assert `sub`, `auth`, and expiration claims.
   - Assert `rememberMe=true` produces a longer expiry than the default token.
   - Add `GET /api/authenticate` coverage with a valid JWT and assert the principal name is returned.

3. **Expand `src/test/java/net/jojoaddison/security/DomainUserDetailsServiceIT.java`.**

   - Add unknown-login coverage and assert `UsernameNotFoundException`.
   - Add unknown-email coverage and assert `UsernameNotFoundException`.
   - Add a user with authorities and assert `UserDetails.getAuthorities()` contains the mapped roles.

4. **Expand `src/test/java/net/jojoaddison/security/SecurityUtilsUnitTest.java`.**
   - Add `getCurrentUserLogin()` coverage for a `Jwt` principal.
   - Add `getCurrentUserLogin()` coverage with no security context and assert an empty result.
   - Add `getCurrentUserJWT()` coverage when credentials are not a `String` and assert an empty result.
   - Add no-context coverage for `hasCurrentUserAnyOfAuthorities(...)`, `hasCurrentUserNoneOfAuthorities(...)`, and `hasCurrentUserThisAuthority(...)`.

## User service and account flows

5. **Expand `src/test/java/net/jojoaddison/service/UserServiceIT.java` for `registerUser(...)`.**

   - Verify login and email are persisted in lowercase.
   - Verify the stored password is encoded, `activated` is `false`, and `activationKey` is generated.
   - Verify only `ROLE_USER` is assigned even if the incoming DTO contains higher privileges.
   - Verify an existing non-activated user with the same login is deleted and replaced.
   - Verify an existing non-activated user with the same email is deleted and replaced.
   - Verify an existing activated user with the same login raises `UsernameAlreadyUsedException`.
   - Verify an existing activated user with the same email raises `EmailAlreadyUsedException`.
   - Verify audit fields fall back to `Constants.SYSTEM` when there is no authenticated principal.

6. **Expand `src/test/java/net/jojoaddison/service/UserServiceIT.java` for `createUser(...)`.**

   - Verify `langKey == null` falls back to `Constants.DEFAULT_LANGUAGE`.
   - Verify admin-created users get an encoded random password, a non-null `resetKey`, a non-null `resetDate`, and `activated=true`.
   - Verify unknown authority names are ignored because only repository hits are added.
   - Verify login and email normalization still occurs.

7. **Expand `src/test/java/net/jojoaddison/service/UserServiceIT.java` for `updateUser(AdminUserDTO)`.**

   - Verify authorities are replaced rather than merged with the existing set.
   - Verify updated login and email are persisted in lowercase.
   - Verify an unknown user id returns an empty result instead of mutating data.

8. **Expand `src/test/java/net/jojoaddison/web/rest/AccountResourceIT.java`.**
   - Add `GET /api/account` coverage for an authenticated principal whose user record no longer exists and assert the controller error path is used.
   - Add `POST /api/account` coverage where the current login exists in the security context but not in the repository and assert the controller returns the error path.
   - Add `POST /api/register` assertions that client-supplied `activated` is ignored and persisted login/email are lowercase.
   - Add `POST /api/account/reset-password/init` coverage for a non-activated user and assert the request returns `200` without creating reset metadata.
   - Add `POST /api/account/reset-password/finish` coverage for an expired reset key and assert the password remains unchanged.

## Admin and public user resources

9. **Expand `src/test/java/net/jojoaddison/web/rest/UserResourceIT.java`.**

   - Add non-admin access tests for all `/api/admin/users` endpoints and assert they are forbidden.
   - Add `GET /api/admin/users` coverage for disallowed sort fields and assert `400 Bad Request`.
   - Add `PUT /api/admin/users` coverage for an unknown user id and assert `404 Not Found`.
   - Add coverage for the `PUT /api/admin/users/{login}` mapping and pin whether the path variable is ignored or must match the body login.
   - Add assertions that create and update flows persist lowercase login/email.
   - Add an update test that proves authorities are replaced, not accumulated.

10. **Expand `src/test/java/net/jojoaddison/web/rest/PublicUserResourceIT.java`.**
    - Verify non-activated users are excluded from `GET /api/users`.
    - Verify invalid sort properties return `400 Bad Request`.
    - Verify pagination headers are present on the response.
    - Verify the response stays limited to `UserDTO` fields (`id`, `login`).

## Gateway and Kafka

11. **Add `src/test/java/net/jojoaddison/web/rest/GatewayResourceIT.java`.**

    - Verify `GatewayResource.activeRoutes()` rewrites the route predicate into `RouteVM.path`.
    - Verify the route id suffix is converted into lowercase `serviceId`.
    - Verify routes whose `serviceId` matches `spring.application.name` are excluded from the response.
    - Verify `DiscoveryClient.getInstances(serviceId)` results are attached to returned routes.
    - Verify an empty route stream returns `200 OK` with an empty list.

12. **Expand `src/test/java/net/jojoaddison/web/rest/PatientGatewayKafkaResourceIT.java`.**

    - Add a test proving `consume()` emits multiple accepted messages in order, not just the first one.

13. **Add `src/test/java/net/jojoaddison/broker/KafkaConsumerTest.java`.**

    - Verify `KafkaConsumer.accept(...)` pushes messages into the `Flux` returned by `getFlux()`.
    - Verify sequential calls to `accept(...)` are observed by subscribers in the same order.

14. **Add `src/test/java/net/jojoaddison/broker/KafkaProducerTest.java`.**
    - Verify `KafkaProducer.get()` returns the current hard-coded producer payload `kakfa_producer`.

## Mail and bootstrap

15. **Expand `src/test/java/net/jojoaddison/service/MailServiceIT.java`.**

    - Add `sendEmailFromTemplate(...)` coverage where `user.getEmail()` is `null` and assert `JavaMailSender.send(...)` is never called.

16. **Add `src/test/java/net/jojoaddison/config/dbmigrations/InitialSetupMigrationIT.java`.**
    - Verify `changeSet()` creates `ROLE_USER` and `ROLE_ADMIN`.
    - Verify seeded user `user` is activated and has only `ROLE_USER`.
    - Verify seeded user `admin` is activated and has both `ROLE_ADMIN` and `ROLE_USER`.
    - Verify both seeded users have `createdBy = Constants.SYSTEM`.
