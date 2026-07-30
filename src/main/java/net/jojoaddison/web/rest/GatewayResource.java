package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.web.rest.vm.RouteVM;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.*;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST controller for managing Gateway configuration.
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayResource {

    private final RouteLocator routeLocator;

    private final DiscoveryClient discoveryClient;

    @Value("${spring.application.name}")
    private String appName;

    public GatewayResource(RouteLocator routeLocator, DiscoveryClient discoveryClient) {
        this.routeLocator = routeLocator;
        this.discoveryClient = discoveryClient;
    }

    /**
     * {@code GET  /routes} : get the active routes.
     *
     * <p>Returns a {@link Mono} rather than building the list eagerly. The previous version called
     * {@code routeLocator.getRoutes().subscribe(...)} and returned the accumulating list immediately, so the
     * response was serialized before — or while — the asynchronous subscription filled it, and the endpoint
     * answered {@code []} however many routes existed.</p>
     *
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the list of routes.
     */
    @GetMapping("/routes")
    @Secured(AuthoritiesConstants.ADMIN)
    public Mono<ResponseEntity<List<RouteVM>>> activeRoutes() {
        return routeLocator
            .getRoutes()
            .map(this::toRouteVM)
            // Exclude the gateway itself: it registers in Consul like any other service.
            .filter(routeVM -> !routeVM.getServiceId().equalsIgnoreCase(appName))
            .flatMap(this::withServiceInstances)
            .collectList()
            .map(ResponseEntity::ok);
    }

    private RouteVM toRouteVM(Route route) {
        RouteVM routeVM = new RouteVM();
        // Manipulate strings to make Gateway routes look like Zuul's
        String predicate = route.getPredicate().toString();
        String path = predicate.substring(predicate.indexOf("[") + 1, predicate.indexOf("]"));
        routeVM.setPath(path);
        routeVM.setServiceId(route.getId().substring(route.getId().indexOf("_") + 1).toLowerCase());
        return routeVM;
    }

    /**
     * {@code DiscoveryClient} is the blocking API, so the lookup is moved to the bounded-elastic scheduler
     * rather than run on an event-loop thread. Calling it inline is what BlockHound flags in tests, and on a
     * loaded gateway it stalls unrelated requests.
     */
    private Mono<RouteVM> withServiceInstances(RouteVM routeVM) {
        return Mono.fromCallable(() -> discoveryClient.getInstances(routeVM.getServiceId()))
            .subscribeOn(Schedulers.boundedElastic())
            .map(instances -> {
                routeVM.setServiceInstances(instances);
                return routeVM;
            });
    }
}
