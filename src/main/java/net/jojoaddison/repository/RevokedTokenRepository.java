package net.jojoaddison.repository;

import net.jojoaddison.domain.RevokedToken;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository for revoked tokens.
 *
 * <p>Lookups are by primary key — the document id is the token's {@code jti} — because this is consulted on every
 * authenticated request and anything more expensive would be felt.</p>
 */
@Repository
public interface RevokedTokenRepository extends ReactiveMongoRepository<RevokedToken, String> {}
