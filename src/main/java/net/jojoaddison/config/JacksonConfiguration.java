package net.jojoaddison.config;

import org.springframework.context.annotation.Configuration;

/**
 * Jackson customizations for this application.
 *
 * <p>Java date and time support needs no module registration under Jackson 3: {@code jackson-databind} ships it in
 * {@code tools.jackson.databind.ext.javatime} and registers it automatically, so the former {@code JavaTimeModule}
 * bean — and the {@code jackson-datatype-jsr310} dependency it came from, which is not published for Jackson 3 —
 * are gone.</p>
 *
 * <p>The class is kept because integration tests import it explicitly
 * ({@code @SpringBootTest(classes = { ..., JacksonConfiguration.class, ... })}) and so future Jackson customizations
 * have an obvious home.</p>
 */
@Configuration
public class JacksonConfiguration {}
