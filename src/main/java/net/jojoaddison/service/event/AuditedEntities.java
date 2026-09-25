package net.jojoaddison.service.event;

import java.util.Set;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.RevokedToken;
import net.jojoaddison.domain.User;

/**
 * Which of this gateway's documents are audit-worthy entity changes, and which are infrastructure noise.
 *
 * <h2>⛔ Never discover entities by enumerating {@code @Document} — it cannot tell these three apart</h2>
 *
 * <p>Backlog item 45. <strong>All three</strong> concrete classes in {@code net.jojoaddison.domain} — {@link User},
 * {@link Authority} and {@link RevokedToken} — carry the annotation, so scanning for it selects the whole domain and
 * discriminates nothing. An implementation built that way would publish role definitions on every boot and a frame per
 * logout, which is exactly the flood the two suppressions below exist to prevent. The annotation says <em>this is a
 * document</em>; it cannot say <em>this is audit-worthy</em>, and no annotation Spring Data owns ever will.</p>
 *
 * <p>⚠ <strong>Item 45's fact 2 asserted the opposite — that {@code User} carries no {@code @Document} at all — and it
 * is false. The way it was false is worth more than the fact.</strong> The probe was
 * {@code grep -n '@Document' src/main/java/net/jojoaddison/domain/*.java}, which returns hits for {@code Authority} and
 * {@code RevokedToken} and nothing for {@code User}. The grep was accurate; the conclusion did not follow, because
 * {@code User} declares it <strong>fully qualified</strong> —
 * {@code @org.springframework.data.mongodb.core.mapping.Document(collection = "jhi_user")} — a form that literal does not
 * match. {@code User.class.isAnnotationPresent(Document.class)} answers {@code true}. A probe answers the question it was
 * pointed at, not the question the sentence written from it claims.</p>
 *
 * <p><strong>The instruction built on that false fact was nevertheless the right one</strong>, which is why this class is
 * shaped the way item 45 asked for: <em>derive what you publish, never enumerate</em>. It holds for a different reason
 * than the one given — over-inclusion rather than silent omission — and it would hold even if the annotations were
 * distributed some third way, because the question this class answers is not one the persistence metadata is about.</p>
 *
 * <p>Hence this class. {@link net.jojoaddison.config.EntityChangeCallback} observes <em>every</em> collection and asks
 * here what to do with each, rather than deriving a set of types from metadata that does not describe them.</p>
 *
 * <h2>The decision is enumerated. The coverage is derived.</h2>
 *
 * <p>Those are different things and conflating them is how an exclusion list rots. Which collections are noise is a
 * judgement and cannot be computed — it is written out below, with the reason per entry. But <em>whether every domain
 * class has been judged</em> is mechanical, and {@code AuditedEntitiesTest} computes it: it walks
 * {@code net.jojoaddison.domain} on the classpath and fails if any concrete class is in neither set. <strong>A new
 * domain class therefore breaks the build until somebody reasons about it</strong>, which is the opposite of the
 * failure this estate keeps finding — a list that enumerates today's three and goes quietly stale.</p>
 *
 * <p>⛔ <strong>Do not "fix" that test by adding the new class to {@link #SUPPRESSED} to get green.</strong> Suppressing
 * is the choice that loses an audit row silently; publishing is the choice that is merely noisy. When unsure, publish.</p>
 *
 * <h2>Why these two are suppressed</h2>
 *
 * <p>hc-admin's {@code AuditLogCallback} is the model for excluding writes that describe the machine rather than a
 * person's record. Both entries here are that, and both were measured rather than assumed:</p>
 *
 * <ul>
 *   <li>{@link Authority} — role <em>definitions</em>, seeded by four Mongock change units that run in every profile on
 *       every boot. A probe run on 2026-09-25 recorded <strong>13 {@code Authority} saves</strong> in a single test
 *       context start. Publishing them would put a burst of identical frames on the topic on every start of every
 *       replica, describing the existence of {@code ROLE_NURSE} rather than anything anybody did. An audit trail of the
 *       role table is not an audit trail.</li>
 *   <li>{@link RevokedToken} — one document per logout. This is an authentication artefact, not a domain change, and
 *       publishing it would <em>create</em> in hc-admin exactly the per-authentication row flood that item 45's fact 6
 *       observes this gateway is free of (there is no {@code persistent_audit_event} writer here — confirmed by grep).
 *       A logout is a security event; the estate's answer for those is the security meters in {@code management}, not a
 *       cross-product audit topic.</li>
 * </ul>
 *
 * <p><strong>Mongock's own {@code mongockChangeLog} and {@code mongockLock} need no entry, and that is an observation
 * rather than an oversight.</strong> Mongock drives its migrations through its own imperative driver and a
 * {@code MongoTemplate} it builds itself, not through the {@code ReactiveMongoTemplate} these events come from, so
 * those two collections raise no lifecycle event here at all. The probe saw {@code Authority} writes during migration
 * and no {@code mongockChangeLog} write — which is also why {@code Authority} needed an entry and they did not.</p>
 */
public final class AuditedEntities {

    /**
     * Documents whose changes are published.
     *
     * <p>{@link User} is the account: created on registration, updated on activation, on a password reset, on a profile
     * edit, on a failed-login count and — the reason item 45 exists — <strong>deactivated by
     * {@link DeletionAccountCloser} when an erasure completes.</strong> hc-admin needs <em>archived</em> (which the api
     * publishes) and <em>deactivated</em> (which is this) to honour an erasure.</p>
     */
    private static final Set<Class<?>> PUBLISHED = Set.of(User.class);

    /** Documents deliberately not published. See the class javadoc for the reason per entry. */
    private static final Set<Class<?>> SUPPRESSED = Set.of(Authority.class, RevokedToken.class);

    private AuditedEntities() {}

    /**
     * Whether a change to this type belongs on {@code patient.event}.
     *
     * @param type the document's class, never null.
     * @return true only for a type explicitly classified as published. <strong>An unclassified type answers
     *     {@code false}</strong> — a new domain class is silent on the topic rather than publishing a frame whose
     *     content nobody has reasoned about. That is the safe direction at runtime, and the reason it does not become a
     *     silent omission is that {@code AuditedEntitiesTest} fails the build first.
     */
    public static boolean isPublished(Class<?> type) {
        return PUBLISHED.contains(type);
    }

    /** Whether this type has been reasoned about at all. Used only by the test that derives coverage. */
    static boolean isClassified(Class<?> type) {
        return PUBLISHED.contains(type) || SUPPRESSED.contains(type);
    }
}
