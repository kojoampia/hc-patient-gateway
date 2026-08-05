package net.jojoaddison.config;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.jojoaddison.management.SecurityMetersService;
import net.jojoaddison.service.TokenRevocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import reactor.core.publisher.Mono;

@Configuration
public class SecurityJwtConfiguration {

    private final Logger log = LoggerFactory.getLogger(SecurityJwtConfiguration.class);

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    /**
     * Origin-validation settings. Injected as typed properties rather than read with {@code @Value} because the
     * {@code application.*} prefix is bound strictly — an unknown key there fails context startup rather than being
     * ignored, so the binding has to be declared.
     */
    private final ApplicationProperties.Security.Jwt jwtProperties;

    private final TokenRevocationService tokenRevocationService;

    public SecurityJwtConfiguration(ApplicationProperties applicationProperties, TokenRevocationService tokenRevocationService) {
        this.jwtProperties = applicationProperties.getSecurity().getJwt();
        this.tokenRevocationService = tokenRevocationService;
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(SecurityMetersService metersService) {
        NimbusReactiveJwtDecoder jwtDecoder = NimbusReactiveJwtDecoder.withSecretKey(getSecretKey()).macAlgorithm(JWT_ALGORITHM).build();
        if (jwtProperties.isValidateOrigin()) {
            // Layered on top of the defaults rather than replacing them: a bare validator here would silently drop
            // the expiry check, which is a worse hole than the one being closed.
            jwtDecoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(),
                    new TokenOriginValidator(jwtProperties.getTrustedIssuers(), jwtProperties.getAudience())
                )
            );
            log.info(
                "JWT origin validation is ON: issuers {} audience '{}'",
                jwtProperties.getTrustedIssuers(),
                jwtProperties.getAudience()
            );
        } else {
            log.info(
                "JWT origin validation is OFF. A token minted by any product sharing this signing key is accepted. " +
                "Enable with application.security.jwt.validate-origin=true once every issuer emits iss/aud."
            );
        }
        return token -> {
            try {
                return jwtDecoder
                    .decode(token)
                    // Revocation is checked HERE rather than in a filter because every request this gateway handles
                    // decodes its token through this bean — both /api/** served locally and /services/** proxied
                    // onward. A filter would have to be registered in two places and would eventually be registered
                    // in one.
                    .flatMap(
                        jwt ->
                            tokenRevocationService
                                .isRevoked(jwt.getId())
                                .flatMap(
                                    revoked ->
                                        Boolean.TRUE.equals(revoked)
                                            ? Mono.<org.springframework.security.oauth2.jwt.Jwt>error(
                                                new org.springframework.security.oauth2.jwt.BadJwtException("The token has been revoked")
                                            )
                                            : Mono.just(jwt)
                                )
                    )
                    .doOnError(e -> {
                        if (e.getMessage().contains("Jwt expired at")) {
                            metersService.trackTokenExpired();
                        } else if (e.getMessage().contains("Failed to validate the token")) {
                            metersService.trackTokenInvalidSignature();
                        } else if (
                            e.getMessage().contains("Invalid JWT serialization:") ||
                            e.getMessage().contains("Invalid unsecured/JWS/JWE header:")
                        ) {
                            metersService.trackTokenMalformed();
                        } else {
                            log.error("Unknown JWT reactive error {}", e.getMessage());
                        }
                    });
            } catch (Exception e) {
                if (e.getMessage().contains("An error occurred while attempting to decode the Jwt")) {
                    metersService.trackTokenMalformed();
                } else if (e.getMessage().contains("Failed to validate the token")) {
                    metersService.trackTokenInvalidSignature();
                } else {
                    log.error("Unknown JWT error {}", e.getMessage());
                }
                throw e;
            }
        };
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(getSecretKey()));
    }

    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("");
        grantedAuthoritiesConverter.setAuthoritiesClaimName(AUTHORITIES_KEY);

        ReactiveJwtAuthenticationConverter jwtAuthenticationConverter = new ReactiveJwtAuthenticationConverter();

        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(
            new ReactiveJwtGrantedAuthoritiesConverterAdapter(grantedAuthoritiesConverter)
        );
        return jwtAuthenticationConverter;
    }

    private SecretKey getSecretKey() {
        // Fail here, loudly, rather than build a zero-length key and fail somewhere unreadable later. The committed
        // literal that used to live in application-prod.yml is gone (2026-08-05); production supplies the key through
        // JWT_BASE64_SECRET, and an environment that forgets to set it must not start rather than start insecurely.
        if (jwtKey == null || jwtKey.isBlank()) {
            throw new IllegalStateException(
                "jhipster.security.authentication.jwt.base64-secret is not set. Provide it via the " +
                "JWT_BASE64_SECRET environment variable (openssl rand -base64 64). There is deliberately no default " +
                "outside the dev and test profiles."
            );
        }
        byte[] keyBytes = Base64.from(jwtKey).decode();
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, JWT_ALGORITHM.getName());
    }
}
