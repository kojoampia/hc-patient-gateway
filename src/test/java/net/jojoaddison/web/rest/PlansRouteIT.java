package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * That the membership-plan route is wired where it is supposed to be.
 *
 * <p>Worth pinning because the failure is silent and remote. Abofonsa is another product on another host, so nothing
 * in this repository's tests can prove the round trip — but a route that points at the wrong path, or that quietly
 * stops being configured, produces a 404 the portal reads as "no plans available" and renders as a calm empty state.
 * The screen would look correct while the feature was entirely gone.</p>
 *
 * <p>The base URL is a property precisely so a quality or local environment can point it somewhere else without
 * editing a route.</p>
 */
@IntegrationTest
class PlansRouteIT {

    @Autowired
    private Environment environment;

    @Test
    void thePlansRouteTargetsAbofonsasContentApi() {
        String prefix = "spring.cloud.gateway.server.webflux.routes[0]";

        assertThat(environment.getProperty(prefix + ".id")).isEqualTo("abofonsa-plans");
        assertThat(environment.getProperty(prefix + ".predicates[0]")).isEqualTo("Path=/api/plans");
        // The portal never needs to know another product's URL shape; the rewrite is what keeps it that way.
        assertThat(environment.getProperty(prefix + ".filters[0]")).isEqualTo("SetPath=/api/v1/content/plans");
        assertThat(environment.getProperty(prefix + ".uri")).contains("abofonsa");
    }
}
