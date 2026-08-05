package net.jojoaddison.service;

import java.time.Instant;
import net.jojoaddison.domain.RevokedToken;
import net.jojoaddison.repository.RevokedTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Makes a signed-out token stop working.
 *
 * <p>Before 2026-08-05 nothing could. Authentication is stateless and logout was a client-side
 * {@code localStorage.removeItem}, so a token that had been "signed out" — or stolen, or leaked in a log — stayed
 * valid until it expired, and the only way to invalidate it was to rotate the signing key, which is shared with
 * hc-admin and hc-professional and would therefore have logged out every user of all three products.</p>
 *
 * <h2>Scope, stated plainly</h2>
 *
 * <p>Revocation is enforced HERE, at the gateway, and not in hc-patient-service. That is sufficient because the
 * microservice publishes no port and is reachable only through this gateway's static route, so every request carrying
 * a revoked token passes through this check first. It would stop being sufficient the moment that service becomes
 * directly reachable — and the fix then is to move this collection somewhere both can read, because they use separate
 * databases today.</p>
 */
@Service
public class TokenRevocationService {

    private static final Logger LOG = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RevokedTokenRepository revokedTokenRepository;

    public TokenRevocationService(RevokedTokenRepository revokedTokenRepository) {
        this.revokedTokenRepository = revokedTokenRepository;
    }

    /**
     * Records a token as revoked until it would have expired.
     *
     * @param tokenId the token's {@code jti}.
     * @param expiresAt when the token would have expired anyway; the row is deleted then by a TTL index.
     * @return completion.
     */
    public Mono<Void> revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null || expiresAt == null) {
            // A token minted before the jti claim existed. Nothing can be recorded against it, and saying so is
            // better than pretending the sign-out did something.
            LOG.warn("Sign-out could not revoke a token that carries no jti or no expiry; it stays valid until it expires");
            return Mono.empty();
        }
        return revokedTokenRepository.save(new RevokedToken(tokenId, expiresAt)).then();
    }

    /**
     * Whether a token has been revoked.
     *
     * @param tokenId the token's {@code jti}, possibly null on a token that predates the claim.
     * @return true if it must be refused.
     */
    public Mono<Boolean> isRevoked(String tokenId) {
        if (tokenId == null) {
            // Fails OPEN, deliberately, and this is the one place in this change where that is the right answer: a
            // token with no jti cannot have been revoked, because revoking requires one. Failing closed here would
            // reject every token issued before this feature existed.
            return Mono.just(false);
        }
        return revokedTokenRepository.existsById(tokenId);
    }
}
