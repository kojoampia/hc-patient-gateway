package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The registration gauges against a real user store.
 *
 * <p>This is the assertion the whole "population, not rate" decision rests on: the number is derived from what is in
 * the database at the moment it is read, so it is right on a restarted process, a restored backup and a hand-seeded
 * quality stack alike — none of which an event counter could be right about, because all three start it from zero.</p>
 *
 * <p>Deltas again, because the store already holds whatever the seed initialisers and the other integration tests
 * put in it.</p>
 */
@IntegrationTest
class RegistrationMetricsSamplerIT {

    private static final String REGISTRATIONS_METER = "account.registrations";

    private static final List<String> LOGINS = List.of("population-yes", "population-no-1", "population-no-2");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegistrationMetricsSampler sampler;

    @Autowired
    private RegistrationMetersService registrationMetersService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void tearDown() {
        LOGINS.forEach(login -> userRepository.findOneByLogin(login).flatMap(userRepository::delete).block());
    }

    @Test
    void theGaugesReportWhatIsInTheStoreRightNow() {
        sampler.sampleOnce().block();
        long activatedBefore = registrationMetersService.activatedCount();
        long notActivatedBefore = registrationMetersService.notActivatedCount();

        saveUser("population-yes", true);
        saveUser("population-no-1", false);
        saveUser("population-no-2", false);

        sampler.sampleOnce().block();

        assertThat(registrationMetersService.activatedCount() - activatedBefore).isEqualTo(1);
        assertThat(registrationMetersService.notActivatedCount() - notActivatedBefore).isEqualTo(2);
        assertThat(population("activated")).isEqualTo(registrationMetersService.activatedCount());
        assertThat(population("not-activated")).isEqualTo(registrationMetersService.notActivatedCount());
    }

    @Test
    void thePopulationFallsAgainWhenAccountsGoAway() {
        // A gauge, not a counter. An account that is deleted, or one that finishes activating, has to be able to
        // take the number back down — which is the property an emission counter could never have had.
        saveUser("population-no-1", false);
        sampler.sampleOnce().block();
        long withPending = registrationMetersService.notActivatedCount();

        userRepository.findOneByLogin("population-no-1").flatMap(userRepository::delete).block();
        sampler.sampleOnce().block();

        assertThat(registrationMetersService.notActivatedCount()).isEqualTo(withPending - 1);
    }

    @Test
    void activatingAnAccountMovesItBetweenTheTwoSeriesWithoutChangingTheTotal() {
        saveUser("population-no-1", false);
        sampler.sampleOnce().block();
        long activated = registrationMetersService.activatedCount();
        long notActivated = registrationMetersService.notActivatedCount();

        User user = userRepository.findOneByLogin("population-no-1").block();
        assertThat(user).isNotNull();
        user.setActivated(true);
        userRepository.save(user).block();

        sampler.sampleOnce().block();

        assertThat(registrationMetersService.activatedCount()).isEqualTo(activated + 1);
        assertThat(registrationMetersService.notActivatedCount()).isEqualTo(notActivated - 1);
    }

    private void saveUser(String login, boolean activated) {
        userRepository.findOneByLogin(login).flatMap(userRepository::delete).block();
        User user = new User();
        user.setLogin(login);
        user.setEmail(login + "@example.com");
        user.setActivated(activated);
        user.setPassword(passwordEncoder.encode("irrelevant-nobody-signs-in-here"));
        userRepository.save(user).block();
    }

    private double population(String state) {
        return meterRegistry.get(REGISTRATIONS_METER).tag("state", state).gauge().value();
    }
}
