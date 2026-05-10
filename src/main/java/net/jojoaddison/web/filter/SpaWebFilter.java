package net.jojoaddison.web.filter;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpaWebFilter implements WebFilter {

    private static final String INDEX_HTML = "/index.html";

    private static final List<String> BACKEND_PATH_PREFIXES = List.of("/api", "/management", "/services", "/v3/api-docs");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (request.getMethod() != HttpMethod.GET || INDEX_HTML.equals(path) || path.contains(".") || isBackendPath(path)) {
            return chain.filter(exchange);
        }

        return chain.filter(exchange.mutate().request(request.mutate().path(INDEX_HTML).build()).build());
    }

    private boolean isBackendPath(String path) {
        return BACKEND_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
