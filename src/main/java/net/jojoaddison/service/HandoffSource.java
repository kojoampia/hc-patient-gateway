package net.jojoaddison.service;

import java.util.List;

/**
 * Which surface may claim it sent a family to registration.
 *
 * <p>{@code web.abofonsa.com} appends {@code ?src=} to the link on its landing page so the funnel can be counted
 * from this end — that site has no analytics by design, so a registration carrying a source is the first stage
 * anybody can measure. See {@code docs/patient-handoff-contract.md}.</p>
 *
 * <h2>The dashboard also allow-lists this, and that is not the control</h2>
 *
 * <p>The registration form filters the value before it posts, which keeps the URL honest for an ordinary visitor.
 * It is worth nothing as a defence: {@code POST /api/register} is public and unauthenticated, so anybody can send
 * whatever they like without going near the form. <strong>This is the allowlist that matters</strong>, and the one
 * in the browser is a convenience.</p>
 *
 * <p>What an open field would cost is not a security incident so much as a quiet one: any value would land on a
 * user record a human reads and a report counts, so a stranger could attribute registrations to a campaign that did
 * not send them and nobody would ever see it happen. The number this parameter exists to produce would stop being
 * worth reading.</p>
 *
 * <p><strong>A new surface needs a line here.</strong> The contract says the site may add surfaces without telling
 * us, and an unlisted one loses its attribution silently rather than loudly. That is the deliberate trade: adding a
 * line is cheap, and un-poisoning a metric is not.</p>
 */
public final class HandoffSource {

    private static final List<String> KNOWN = List.of("web-home");

    private HandoffSource() {}

    /**
     * @param src whatever arrived on the request, which is to say the caller's own text.
     * @return the source when it is one we agreed to, otherwise null — never what the caller sent.
     */
    public static String recognised(String src) {
        return src != null && KNOWN.contains(src) ? src : null;
    }
}
