package net.jojoaddison.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.service.dto.CareAngelAccountDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.jhipster.security.RandomUtil;

/**
 * Creates — or finds — the account a nominated care angel will sign in with.
 *
 * <h2>Two branches, and the difference matters</h2>
 *
 * <p>If the nominated email already has an account, <strong>no second one is created</strong>. The existing account is
 * granted {@code ROLE_ANGEL} and told it has been nominated. Creating a duplicate would leave one person with two
 * logins, one of which they never chose and cannot merge, and would break the very lookup a delegation depends on.</p>
 *
 * <p>Otherwise an account is created with a login derived from their name and a password nobody knows — a random UUID
 * that is never displayed, logged or returned. The account is created <em>already activated</em>, and the invitation
 * is the ordinary password-reset mail. That satisfies "cannot authenticate until they set a password" by construction
 * rather than by a new flag: the UUID is unknown to everyone including them, so the emailed reset link is the only way
 * in. It also means {@code GET /api/activate} keeps its contract and the web activation screen needs no change.</p>
 *
 * <h2>What this class does not decide</h2>
 *
 * <p>Nothing here grants access to a patient's record. {@code ROLE_ANGEL} is informational; the authority to act for
 * somebody comes from an active {@code CareDelegation} in the patient service, which is created separately and only
 * takes effect when this person accepts it. An account made here and never accepted can see nothing.</p>
 */
@Service
public class CareAngelService {

    private final Logger log = LoggerFactory.getLogger(CareAngelService.class);

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;

    public CareAngelService(UserRepository userRepository, AuthorityRepository authorityRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Finds or creates the angel's account.
     *
     * @param request the nominee's details.
     * @return the account, and whether it already existed — the caller needs to know which mail to send.
     */
    public Mono<CareAngelAccountDTO> findOrCreate(CareAngelRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        return userRepository
            .findOneByEmailIgnoreCase(email)
            .flatMap(
                existing -> grantAngelRole(existing).map(saved -> new CareAngelAccountDTO(saved.getLogin(), saved.getEmail(), true, null))
            )
            .switchIfEmpty(Mono.defer(() -> create(request, email)));
    }

    private Mono<CareAngelAccountDTO> create(CareAngelRequest request, String email) {
        return availableLogin(deriveLogin(request.firstName(), request.lastName(), email)).flatMap(login -> {
            User user = new User();
            user.setLogin(login);
            user.setEmail(email);
            user.setFirstName(request.firstName());
            user.setLastName(request.lastName());
            user.setLangKey(request.langKey() == null ? "en" : request.langKey());
            // Nobody knows this, including the person it belongs to. The reset mail is the only way in.
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            // Activated on creation: see the class comment. The invitation is a password reset, not an activation.
            user.setActivated(true);
            user.setResetKey(RandomUtil.generateResetKey());
            user.setResetDate(java.time.Instant.now());

            // concatMap and collect rather than flatMap into a shared HashSet: flatMap resolves its sources
            // concurrently, and two threads writing a plain set is a race that only shows up under load.
            return Flux.fromIterable(List.of(AuthoritiesConstants.USER, AuthoritiesConstants.ANGEL))
                .concatMap(authorityRepository::findById)
                .collect(Collectors.toCollection(HashSet<Authority>::new))
                .map(authorities -> {
                    user.setAuthorities(authorities);
                    return user;
                })
                .flatMap(userRepository::save)
                .doOnNext(saved -> log.debug("Created a care angel account: {}", saved.getLogin()))
                .map(saved -> new CareAngelAccountDTO(saved.getLogin(), saved.getEmail(), false, saved.getResetKey()));
        });
    }

    private Mono<User> grantAngelRole(User existing) {
        boolean alreadyAngel = existing.getAuthorities().stream().anyMatch(a -> AuthoritiesConstants.ANGEL.equals(a.getName()));
        if (alreadyAngel) {
            return Mono.just(existing);
        }
        return authorityRepository
            .findById(AuthoritiesConstants.ANGEL)
            .doOnNext(angel -> existing.getAuthorities().add(angel))
            .then(userRepository.save(existing));
    }

    /**
     * Finds a free login, appending the smallest numeric suffix that is not taken.
     *
     * <p>Collisions are ordinary rather than exceptional: {@code Grace Mensah} and {@code Gale Mensah} both derive
     * {@code ge_mensah}.</p>
     */
    private Mono<String> availableLogin(String base) {
        return userRepository.findOneByLogin(base).flatMap(taken -> availableLogin(base, 2)).switchIfEmpty(Mono.just(base));
    }

    private Mono<String> availableLogin(String base, int suffix) {
        String candidate = base + suffix;
        return userRepository
            .findOneByLogin(candidate)
            .flatMap(taken -> availableLogin(base, suffix + 1))
            .switchIfEmpty(Mono.just(candidate));
    }

    /**
     * {@code Grace Mensah} becomes {@code ge_mensah}: first initial, last letter of the first name, underscore,
     * surname.
     *
     * <p>Four things this has to survive, all of which are real names rather than hypotheticals:</p>
     *
     * <ul>
     *   <li><strong>Accents.</strong> {@code Constants.LOGIN_REGEX} permits only {@code [_.@A-Za-z0-9-]}, so
     *       {@code Ámà} is transliterated before it is used. A login the regex would reject is worse than an
     *       approximate one — the account simply could not be created.</li>
     *   <li><strong>A one-character first name.</strong> First and last letter are the same, giving {@code aa_}. That
     *       is legal and unambiguous, and is deliberately not "fixed".</li>
     *   <li><strong>A missing surname.</strong> Falls back to the email's local part rather than producing a login
     *       ending in an underscore.</li>
     *   <li><strong>Nothing usable at all.</strong> Falls back to the local part too, so a nomination is never
     *       refused for being hard to spell.</li>
     * </ul>
     */
    static String deriveLogin(String firstName, String lastName, String email) {
        String first = asciiOnly(firstName);
        String last = asciiOnly(lastName);
        String fallback = asciiOnly(email == null ? "" : email.split("@")[0]);

        if (first.isEmpty() || last.isEmpty()) {
            return fallback.isEmpty() ? "angel" : fallback;
        }
        String initials = "" + first.charAt(0) + first.charAt(first.length() - 1);
        return (initials + "_" + last).toLowerCase(Locale.ROOT);
    }

    /** Strips accents and anything the login pattern would refuse. */
    private static String asciiOnly(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * What the patient tells us about their nominee.
     *
     * @param langKey the nominee's language, so the invitation is not sent in the patient's.
     */
    public record CareAngelRequest(String firstName, String lastName, String email, String phone, String langKey) {}
}
