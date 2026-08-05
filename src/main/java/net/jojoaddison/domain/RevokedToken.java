package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A token that has been signed out and must no longer be accepted.
 *
 * <p>Authentication here is stateless, which is what made a signed-out token keep working: nothing recorded the fact,
 * so logout was a client-side {@code localStorage.removeItem} and the credential itself stayed valid until it expired.
 * Shortening lifetimes narrowed that window; this closes it.</p>
 *
 * <p>The document id IS the token's {@code jti}, so revoking twice is idempotent and checking costs a primary-key
 * lookup rather than a query.</p>
 *
 * <h2>Why the collection cannot grow without bound</h2>
 *
 * <p>{@code expiresAt} carries a TTL index with a zero-second delay, so MongoDB deletes each row the moment the token
 * it describes would have expired anyway. Beyond that instant the entry proves nothing the expiry check does not
 * already prove, and a revocation list that only ever grows is the thing that makes people abandon revocation.</p>
 */
@Document(collection = "revoked_token")
public class RevokedToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The revoked token's {@code jti}. */
    @Id
    private String id;

    /**
     * When the token would have expired on its own.
     *
     * <p>The TTL index removes the document at exactly this time — {@code expireAfterSeconds: 0} means "expire at the
     * value of this field", not "expire immediately", which is the reading that makes people avoid the feature.</p>
     */
    @Indexed(name = "revoked_token_ttl", expireAfterSeconds = 0)
    @Field("expires_at")
    private Instant expiresAt;

    public RevokedToken() {}

    public RevokedToken(String id, Instant expiresAt) {
        this.id = id;
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
