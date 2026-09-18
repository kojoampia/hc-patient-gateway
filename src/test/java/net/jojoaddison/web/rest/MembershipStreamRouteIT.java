package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jojoaddison.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.MediaType;

/**
 * The route that carries the patient's membership stream, read back out of the framework rather than out of the file
 * that declares it.
 *
 * <h2>Two settings, both of which fail by working slightly less well</h2>
 *
 * <p><strong>No response timeout.</strong> Spring Cloud Gateway applies
 * {@code spring.cloud.gateway.server.webflux.httpclient.response-timeout} to every proxied response, and a
 * {@code text/event-stream} held open for a patient waiting on the back office is a response that never finishes.
 * Nothing sets a timeout in this repository today — which is exactly what makes the route metadata easy to leave out
 * and impossible to miss once it is: the stream works in development and is cut with a 504 the first time an
 * environment sets one, in an interval nobody would connect to routing.</p>
 *
 * <p><strong>Buffering off.</strong> {@code NettyWriteResponseFilter} flushes per chunk only for the configured
 * streaming media types. Drop {@code text/event-stream} from that list and every push is held until the connection
 * closes, which for this feature is never — a stream that delivers nothing, over a route that answers 200.</p>
 *
 * <h2>Why the resolved route and not the property</h2>
 *
 * <p>{@code PlansRouteIT} reads the {@code Environment}, which is enough for a route whose failure is a 404 somebody
 * will notice. It is not enough here. This gateway has already shipped a whole block of routing configuration bound to
 * <em>nothing</em> — Spring Cloud Gateway 5 moved every server property under {@code server.webflux}, and at the old
 * paths the discovery locator produced no routes at all while {@code GET /api/gateway/routes} returned {@code []} and
 * unauthenticated callers still got a 401 that hid it. A property read would have agreed with the file and told us
 * nothing. {@link RouteLocator} and {@link GatewayProperties} are what the request path actually consults.</p>
 *
 * <p><b>What this cannot see</b> is the route in force anywhere it matters. Production and quality both set
 * {@code SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_ROUTES_*} in their compose files, and Spring Boot does not merge a
 * collection across property sources — so the highest-precedence source supplies the whole list and this route is
 * <em>not in it</em> until those files are edited. That is a change in {@code deploy/} and {@code quality/}, sibling
 * repositories this module's CI never checks out. The same limitation {@code GatewayRoutePolicyTest} documents for the
 * cross-stack predicates, and it is why this test is evidence about the declaration rather than about the deployment.
 * </p>
 */
@IntegrationTest
class MembershipStreamRouteIT {

    private static final String ROUTE_ID = "hcpatientservice-membership-stream";

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void theStreamRouteCarriesNoResponseTimeout() {
        Route route = routeNamed(ROUTE_ID);

        // NettyRoutingFilter reads this key and treats any negative value as "no timeout". Asserted as "negative"
        // rather than "equal to -1" because -1 is one spelling of the decision and the decision is what matters.
        Object timeout = route.getMetadata().get("response-timeout");
        assertThat(timeout).as("the route carries no response-timeout metadata at all — the stream will be cut").isNotNull();
        assertThat(Long.parseLong(String.valueOf(timeout)))
            .as("a non-negative response timeout cuts a stream that is behaving correctly")
            .isNegative();
    }

    @Test
    void theStreamRouteIsOrderedAheadOfTheServicePrefixItNarrows() {
        // The broad /services/hcpatientservice/** route — static in the deployed environments, from the discovery
        // locator in development — matches this path too. It carries no metadata, so losing the race means inheriting
        // the default response timeout, which does not fail: the stream is simply cut.
        assertThat(routeNamed(ROUTE_ID).getOrder()).isNegative();
    }

    /**
     * <b>Read this one for exactly what it says.</b> {@code text/event-stream} is a streaming media type in the
     * resolved {@link GatewayProperties}, so {@code NettyWriteResponseFilter} flushes per chunk rather than collecting
     * the response. That is the property the feature needs.
     *
     * <p>It is <em>not</em> evidence that the declaration in {@code application.yml} bound: the declared value equals
     * the framework default, so a mistyped key leaves the correct answer behind and this passes anyway. What it does
     * catch is an environment — or a future edit — that takes {@code text/event-stream} out, which would hold every
     * patient's push until the connection closed with nothing failing anywhere.</p>
     */
    @Test
    void serverSentEventsAreFlushedPerChunkRatherThanCollected() {
        assertThat(gatewayProperties.getStreamingMediaTypes())
            .as("text/event-stream must stay a streaming type, or every push is held until the connection closes")
            .contains(MediaType.TEXT_EVENT_STREAM);
    }

    /** A locator that resolves nothing would make every assertion above vacuous. */
    @Test
    void theRouteIsActuallyResolvedByTheLocator() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).as("routes resolved by the locator").isNotNull().contains(ROUTE_ID);
    }

    private Route routeNamed(String id) {
        Route route = routeLocator.getRoutes().filter(candidate -> id.equals(candidate.getId())).blockFirst();
        assertThat(route).as("no route resolved with id %s", id).isNotNull();
        return route;
    }
}
