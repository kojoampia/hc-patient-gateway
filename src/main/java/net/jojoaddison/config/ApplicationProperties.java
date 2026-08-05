package net.jojoaddison.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to PatientGateway.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {

    private final Security security = new Security();

    public Security getSecurity() {
        return security;
    }

    /**
     * Security settings this application owns, as distinct from the ones JHipsterProperties owns.
     *
     * <p>They live here rather than under {@code jhipster.security.*} because JHipsterProperties binds with
     * {@code ignoreUnknownFields = false}: an extra key under its prefix is not ignored, it fails context startup with
     * an unbound-property error. This class has the same strictness, which is why the nested types below exist rather
     * than the properties being read with a bare {@code @Value}.</p>
     */
    public static class Security {

        private final Jwt jwt = new Jwt();

        public Jwt getJwt() {
            return jwt;
        }

        public static class Jwt {

            /**
             * Whether to reject tokens minted for a different Health Connect product.
             *
             * <p>Off by default, and that default is load-bearing — see {@link TokenOriginValidator}. Turning it on
             * rejects every token that lacks {@code iss}/{@code aud}, which is every token in flight at the moment it
             * is switched on, and every token a sibling product issues until it emits its own.</p>
             */
            private boolean validateOrigin = false;

            /** Issuers whose tokens this service accepts, once {@link #validateOrigin} is on. */
            private List<String> trustedIssuers = List.of("hc-patient-gateway");

            /** The audience a token must name to be accepted here. */
            private String audience = "hc-patient";

            public boolean isValidateOrigin() {
                return validateOrigin;
            }

            public void setValidateOrigin(boolean validateOrigin) {
                this.validateOrigin = validateOrigin;
            }

            public List<String> getTrustedIssuers() {
                return trustedIssuers;
            }

            public void setTrustedIssuers(List<String> trustedIssuers) {
                this.trustedIssuers = trustedIssuers;
            }

            public String getAudience() {
                return audience;
            }

            public void setAudience(String audience) {
                this.audience = audience;
            }
        }
    }
    // jhipster-needle-application-properties-property

    // jhipster-needle-application-properties-property-getter

    // jhipster-needle-application-properties-property-class
}
