package net.jojoaddison.security.jwt;

import net.jojoaddison.service.TokenRevocationService;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

/**
 * Supplies a revocation service to the narrow token-metering contexts.
 *
 * <p>{@code SecurityJwtConfiguration} consults {@link TokenRevocationService} on every decode, and the real one needs
 * a reactive MongoDB repository. These contexts list their beans explicitly and start no database, so they get a stub
 * that revokes nothing — which is the right answer for them: they are about how malformed, expired and
 * wrongly-signed tokens are counted, and revocation is covered end to end by {@code TokenRevocationIT}.</p>
 */
@TestConfiguration
public class TokenRevocationStubConfiguration {

    @Bean
    TokenRevocationService tokenRevocationService() {
        TokenRevocationService stub = Mockito.mock(TokenRevocationService.class);
        Mockito.when(stub.isRevoked(ArgumentMatchers.any())).thenReturn(Mono.just(false));
        return stub;
    }
}
