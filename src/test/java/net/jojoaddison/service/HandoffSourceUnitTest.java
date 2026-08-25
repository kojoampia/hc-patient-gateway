package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What may be recorded as having sent a family here.
 *
 * <p>These are the cases that separate attribution from "anybody may write anything into our numbers". The
 * dashboard filters the same value before posting, but {@code POST /api/register} is public and unauthenticated —
 * so the browser's copy is a convenience and this is the control.</p>
 */
class HandoffSourceUnitTest {

    @Test
    void acceptsASurfaceTheContractNames() {
        assertThat(HandoffSource.recognised("web-home")).isEqualTo("web-home");
    }

    @Test
    void isNullWhenNobodySaid() {
        assertThat(HandoffSource.recognised(null)).isNull();
        assertThat(HandoffSource.recognised("")).isNull();
    }

    @Test
    void refusesASurfaceNobodyAgreedTo() {
        // The stated cost of the allowlist: a genuinely new surface loses attribution until a line is added.
        assertThat(HandoffSource.recognised("web-pricing")).isNull();
    }

    @Test
    void neverReturnsTheCallersOwnText() {
        // Not a hypothetical -- the endpoint is public, so this is the only thing standing between a stranger and
        // a value on a user record that a human reads and a report counts.
        assertThat(HandoffSource.recognised("<script>alert(1)</script>")).isNull();
        assertThat(HandoffSource.recognised("web-home ")).isNull();
        assertThat(HandoffSource.recognised("WEB-HOME")).isNull();
        assertThat(HandoffSource.recognised("web-home,web-home")).isNull();
    }
}
