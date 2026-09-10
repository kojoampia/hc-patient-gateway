package net.jojoaddison.web.rest;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.EMAIL_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jojoaddison.domain.User;
import net.jojoaddison.management.LoginMetersService;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.UserNotActivatedException;
import net.jojoaddison.service.LoginAttemptService;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Controller to authenticate users.
 */
@RestController
@RequestMapping("/api")
public class AuthenticateController {

    /**
     * Identifies this gateway as the minter of a token. Must match the issuer the patient microservice validates
     * once validation is switched on, and must differ from what hc-admin and hc-professional use — telling the three
     * apart is the entire point.
     */
    public static final String ISSUER = "hc-patient-gateway";

    /**
     * The subsystem a token is good for. hc-admin and hc-professional get their own; a token is then accepted only by
     * the product it was minted for, even though all three verify with the same key.
     */
    public static final String AUDIENCE = "hc-patient";

    private final Logger log = LoggerFactory.getLogger(AuthenticateController.class);

    private final JwtEncoder jwtEncoder;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds:0}")
    private long tokenValidityInSeconds;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds-for-remember-me:0}")
    private long tokenValidityInSecondsForRememberMe;

    private final ReactiveAuthenticationManager authenticationManager;

    private final UserRepository userRepository;

    private final LoginAttemptService loginAttemptService;

    /**
     * Every sign-in outcome is counted from this class and from nowhere else.
     *
     * <p>This is the only place all three failure causes are visible at once. {@code LoginAttemptService} sees two
     * of them; the third — an attempt refused because the account is already locked — short-circuits in
     * {@link #authorize} and never reaches it. See {@link LoginMetersService} for what each number means.</p>
     */
    private final LoginMetersService loginMetersService;

    public AuthenticateController(
        JwtEncoder jwtEncoder,
        ReactiveAuthenticationManager authenticationManager,
        UserRepository userRepository,
        LoginAttemptService loginAttemptService,
        LoginMetersService loginMetersService
    ) {
        this.jwtEncoder = jwtEncoder;
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.loginAttemptService = loginAttemptService;
        this.loginMetersService = loginMetersService;
    }

    @PostMapping("/authenticate")
    public Mono<ResponseEntity<JWTToken>> authorize(@Valid @RequestBody Mono<LoginVM> loginVM) {
        return loginVM
            .flatMap(login ->
                // Checked before the password is, so a locked account costs an attacker one cheap lookup rather than a
                // BCrypt verification — which is the other reason unlimited login attempts hurt: BCrypt is expensive by
                // design and this gateway runs on a 256 MB heap on a shared host.
                loginAttemptService
                    .isLocked(login.getUsername())
                    .flatMap(locked -> {
                        if (!Boolean.TRUE.equals(locked)) {
                            return authenticateAndMint(login);
                        }
                        // Counted HERE, and it cannot be moved into LoginAttemptService: this branch returns without
                        // calling anything on it, so instrumenting only recordFailure would leave the dashboard silent
                        // for the whole of a lockout window — precisely when the account is being hammered.
                        // LoginMetersIT#aRefusedAttemptAgainstALockedAccountIsCounted fails if this line goes away.
                        loginMetersService.trackFailedLoginAccountLocked();
                        // The same 401 a wrong password gets. Saying "locked" would confirm the account exists, and
                        // enumeration is what the attacker doing this is building towards.
                        return Mono.<String>error(new BadCredentialsException("Authentication failed"));
                    }))
            .map(jwt -> {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.setBearerAuth(jwt);
                return new ResponseEntity<>(new JWTToken(jwt), httpHeaders, HttpStatus.OK);
            });
    }

    /**
     * Verifies the password, records the outcome against the account, and mints the token on success.
     *
     * <p>The failure counter is updated on the error path rather than in an exception handler so that the two can
     * never drift apart — a login that fails without being counted is a login that can be retried forever.</p>
     *
     * <p>The metric hangs off the same call. {@code recordFailure} answers whether this failure <em>started</em> a
     * run against a known account, and only then is anything counted: repeated guesses inside one failing episode
     * do not inflate the number, and an unknown login — for which that Mono is empty — is counted nowhere at all,
     * which is what stops the metric becoming the account-existence oracle the 401 refuses to be.</p>
     */
    private Mono<String> authenticateAndMint(LoginVM login) {
        return authenticationManager
            .authenticate(new UsernamePasswordAuthenticationToken(login.getUsername(), login.getPassword()))
            .onErrorResume(
                error ->
                    loginAttemptService
                        .recordFailure(login.getUsername())
                        .filter(Boolean::booleanValue)
                        .doOnNext(firstOfRun -> trackFailure(error))
                        .then(Mono.error(error))
            )
            .flatMap(auth ->
                loginAttemptService
                    .recordSuccess(login.getUsername())
                    .then(
                        userRepository
                            .findOneByLogin(auth.getName())
                            .mapNotNull(User::getEmail)
                            // An account with no email still gets a token, just an unscoped one. The microservice
                            // treats a missing email claim as "no patient records at all", which fails closed.
                            .defaultIfEmpty("")
                            .map(email -> this.createToken(auth, email, login.isRememberMe()))
                    ))
            // Counted once a token actually exists, not once the password matched — those differ if minting throws,
            // and a success the caller never received is not a success.
            .doOnNext(token -> loginMetersService.trackLoginSuccess());
    }

    /**
     * Maps an authentication failure to the {@code cause} tag it is counted under.
     *
     * <p>Two causes reach here; the third, {@code account-locked}, never does — it short-circuits in
     * {@link #authorize} before the authentication manager is consulted. Anything that is not a refusal to serve a
     * never-activated account is counted as bad credentials, which is deliberate: an unrecognised failure is still
     * a failed sign-in, and dropping it would understate the total rather than merely mis-attribute it.</p>
     *
     * @param error whatever {@code authenticationManager.authenticate} signalled.
     */
    private void trackFailure(Throwable error) {
        if (isNotActivated(error)) {
            loginMetersService.trackFailedLoginNotActivated();
        } else {
            loginMetersService.trackFailedLoginBadCredentials();
        }
    }

    /**
     * Whether this failure was {@code DomainUserDetailsService} refusing a never-activated account.
     *
     * <p>Walks the cause chain rather than testing the top frame alone. Spring Security wraps a
     * {@code UserDetailsService} failure in {@code InternalAuthenticationServiceException} in some configurations
     * and not in others, and a metric that silently reclassifies every not-activated refusal as bad credentials the
     * day that changes is worse than no metric — it would read as a password problem.</p>
     */
    private static boolean isNotActivated(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause() == cursor ? null : cursor.getCause()) {
            if (cursor instanceof UserNotActivatedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code GET /authenticate} : check if the user is authenticated, and return its login.
     *
     * @param request the HTTP request.
     * @return the login if the user is authenticated.
     */
    @GetMapping("/authenticate")
    public Mono<String> isAuthenticated(ServerWebExchange request) {
        log.debug("REST request to check if the current user is authenticated");
        return request.getPrincipal().map(Principal::getName);
    }

    /**
     * Mints the session token.
     *
     * <p>The {@code email} claim is what lets the microservice work out <em>which patient</em> is calling. It has no
     * user management of its own ({@code skipUserManagement}), so before this claim existed the only identity it
     * received was the login — which matches nothing in its data — and the practical consequence was that it
     * authorized every request on "is authenticated" alone. It resolves the claim to a Profile, and from there to the
     * {@code patientId} that scopes every query. An absent or unknown email therefore means no records, not all of
     * them.</p>
     *
     * @param authentication the authenticated principal.
     * @param email the account's email address, or empty if it has none.
     * @param rememberMe whether to issue a long-lived token.
     */
    public String createToken(Authentication authentication, String email, boolean rememberMe) {
        String authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.joining(" "));

        Instant now = Instant.now();
        Instant validity;
        if (rememberMe) {
            validity = now.plus(this.tokenValidityInSecondsForRememberMe, ChronoUnit.SECONDS);
        } else {
            validity = now.plus(this.tokenValidityInSeconds, ChronoUnit.SECONDS);
        }

        // @formatter:off
        JwtClaimsSet claims = JwtClaimsSet.builder()
            // A per-token id, so a single session can be revoked without touching the signing key — which is shared
            // with hc-admin and hc-professional, and rotating it would sign every one of their users out too.
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(validity)
            .subject(authentication.getName())
            // Issuer and audience exist because the HMAC signing key is SHARED with hc-admin and hc-professional
            // (see deploy/prod-server/.env.example). A shared key with no issuer and no audience means a token minted
            // by any one of the three validates perfectly at the other two, carrying whatever authorities it was
            // given — ROLE_ADMIN in one product is ROLE_ADMIN in all three, and they do not mean the same thing.
            //
            // NOTE: nothing VALIDATES these yet, so they do not close the hole on their own. That is deliberate
            // sequencing, not an oversight. Every token in flight today lacks these claims, and all three products
            // share the key — turn on validation before the claims are universally present and every user of every
            // product is logged out at once. The order is: ship the claims here, wait for tokens to turn over
            // (an hour, since F9 shortened the lifetime), get the sibling products emitting theirs, then enable the
            // issuer and audience validators on both decoders. Tracked as the second half of finding 3.
            .issuer(ISSUER)
            .audience(java.util.List.of(AUDIENCE))
            .claim(AUTHORITIES_KEY, authorities)
            .claim(EMAIL_KEY, email == null ? "" : email)
            .build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    /**
     * Object to return as body in JWT Authentication.
     */
    static class JWTToken {

        private String idToken;

        JWTToken(String idToken) {
            this.idToken = idToken;
        }

        @JsonProperty("id_token")
        String getIdToken() {
            return idToken;
        }

        void setIdToken(String idToken) {
            this.idToken = idToken;
        }
    }
}
