package net.jojoaddison.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * Rejects tokens minted for a different Health Connect product.
 *
 * <p>The HMAC signing key is shared with hc-admin and hc-professional — one key, three products, by design, so that a
 * session works across them. The consequence nobody chose is that a token minted by any one of the three verifies
 * perfectly at the other two, carrying whatever authorities it was given. {@code ROLE_ADMIN} in the admin console
 * becomes {@code ROLE_ADMIN} here, and those are not the same job.</p>
 *
 * <p>This gateway has emitted {@code iss} and {@code aud} since 2026-08-05. This is the other half: the check that
 * makes them mean something. It matters here as well as in the microservice — the gateway validates the tokens it
 * receives on every proxied request, so a token from a sibling product reaches the routing layer first.</p>
 *
 * <h2>Why it is off by default</h2>
 *
 * <p>Turning this on rejects every token that lacks the claims — which is every token in flight at the moment it is
 * switched on, and every token the sibling products issue until they emit their own. Enabled globally on day one it
 * would log out every user of all three products at once.</p>
 *
 * <p>The properties live under {@code application.*} rather than {@code jhipster.*} deliberately: JHipsterProperties
 * binds with {@code ignoreUnknownFields = false}, so an extra key under its prefix does not get ignored — it fails
 * the whole context startup with an unbound-property error.</p>
 *
 * <p>So it ships disabled and is enabled per environment with
 * {@code application.security.jwt.validate-origin=true}, once:</p>
 *
 * <ol>
 *   <li>the claims have been live longer than the longest token lifetime (7 days, the remember-me window), so no
 *       valid token predates them; and</li>
 *   <li>hc-admin and hc-professional emit their own {@code iss}/{@code aud}, or are accepted here by adding their
 *       issuers to {@code trusted-issuers}.</li>
 * </ol>
 *
 * <p>A flag makes that a one-line config change per environment and an instant rollback, rather than a code release
 * on the day something goes wrong.</p>
 */
public class TokenOriginValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger LOG = LoggerFactory.getLogger(TokenOriginValidator.class);

    private final List<String> trustedIssuers;
    private final String requiredAudience;

    public TokenOriginValidator(List<String> trustedIssuers, String requiredAudience) {
        this.trustedIssuers = List.copyOf(trustedIssuers);
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        // getClaimAsString, NOT getIssuer(). Spring's convenience getter coerces `iss` to a java.net.URL and
        // throws IllegalArgumentException on anything else — and ours is "hc-patient-gateway", a plain string, which
        // RFC 7519 explicitly permits (StringOrURI). Using the typed getter here meant this validator threw on every
        // request the moment it was enabled, rather than validating anything.
        String issuer = token.getClaimAsString(JwtClaimNames.ISS);
        if (issuer == null || !trustedIssuers.contains(issuer)) {
            LOG.warn("Rejected a token whose issuer is not trusted by this service");
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_issuer", "The token was not issued by a trusted issuer", null)
            );
        }
        List<String> audience = token.getAudience();
        if (audience == null || !audience.contains(requiredAudience)) {
            LOG.warn("Rejected a token that is not addressed to this subsystem");
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_audience", "The token was not issued for this subsystem", null)
            );
        }
        return OAuth2TokenValidatorResult.success();
    }
}
