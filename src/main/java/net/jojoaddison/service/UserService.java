package net.jojoaddison.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.AuthorityRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.dto.AdminUserDTO;
import net.jojoaddison.service.dto.UserDTO;
import net.jojoaddison.service.dto.UsernameAvailabilityDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.jhipster.security.RandomUtil;

/**
 * Service class for managing users.
 */
@Service
public class UserService {

    private final Logger log = LoggerFactory.getLogger(UserService.class);

    /** How many free alternatives the look-ahead offers when a login is taken. */
    static final int MAX_SUGGESTIONS = 3;

    /**
     * Compiled once for the class rather than per call. Every candidate is re-checked against it, so
     * String.matches() here would recompile the expression sixteen times per taken username.
     */
    private static final Pattern LOGIN_PATTERN = Pattern.compile(Constants.LOGIN_REGEX);

    /** Matches the {@code @Size} on ManagedUserVM.login; a longer suggestion could not be registered. */
    private static final int MAX_LOGIN_LENGTH = 50;

    /**
     * Tried in order. Plain digits first because they read as the obvious next choice, then a
     * separator form for anyone whose name already ends in a number, where "kojo1" -> "kojo11" is
     * more confusing than helpful. Enough entries that three are still found when the popular
     * variants are also taken, and short enough that the sequential lookup stays cheap.
     */
    private static final List<String> SUGGESTION_SUFFIXES = List.of(
        "1",
        "2",
        "3",
        "4",
        "5",
        "7",
        "9",
        "10",
        "21",
        "42",
        "77",
        "99",
        ".1",
        ".2",
        "-1",
        "-2"
    );

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthorityRepository authorityRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthorityRepository authorityRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authorityRepository = authorityRepository;
    }

    public Mono<User> activateRegistration(String key) {
        log.debug("Activating user for activation key {}", key);
        return userRepository
            .findOneByActivationKey(key)
            .flatMap(user -> {
                // activate given user for the registration key.
                user.setActivated(true);
                user.setActivationKey(null);
                return saveUser(user);
            })
            .doOnNext(user -> log.debug("Activated user: {}", user));
    }

    public Mono<User> completePasswordReset(String newPassword, String key) {
        log.debug("Reset user password for reset key {}", key);
        return userRepository
            .findOneByResetKey(key)
            .filter(user -> user.getResetDate().isAfter(Instant.now().minus(1, ChronoUnit.DAYS)))
            .publishOn(Schedulers.boundedElastic())
            .map(user -> {
                user.setPassword(passwordEncoder.encode(newPassword));
                user.setResetKey(null);
                user.setResetDate(null);
                return user;
            })
            .flatMap(this::saveUser);
    }

    public Mono<User> requestPasswordReset(String mail) {
        return userRepository
            .findOneByEmailIgnoreCase(mail)
            .filter(User::isActivated)
            .publishOn(Schedulers.boundedElastic())
            .map(user -> {
                user.setResetKey(RandomUtil.generateResetKey());
                user.setResetDate(Instant.now());
                return user;
            })
            .flatMap(this::saveUser);
    }

    public Mono<User> registerUser(AdminUserDTO userDTO, String password) {
        return registerUser(userDTO, password, null);
    }

    /**
     * @param source which surface sent them, already checked against the allowlist by the caller, or null.
     *     Taken as a parameter rather than read off the DTO so that the only way to set it is to have passed it
     *     through {@code HandoffSource} — a field on the DTO could be forwarded from anywhere by accident.
     */
    public Mono<User> registerUser(AdminUserDTO userDTO, String password, String source) {
        return userRepository
            .findOneByLogin(userDTO.getLogin().toLowerCase())
            .flatMap(existingUser -> {
                if (!existingUser.isActivated()) {
                    return userRepository.delete(existingUser);
                } else {
                    return Mono.error(new UsernameAlreadyUsedException());
                }
            })
            .then(userRepository.findOneByEmailIgnoreCase(userDTO.getEmail()))
            .flatMap(existingUser -> {
                if (!existingUser.isActivated()) {
                    return userRepository.delete(existingUser);
                } else {
                    return Mono.error(new EmailAlreadyUsedException());
                }
            })
            .publishOn(Schedulers.boundedElastic())
            .then(
                Mono.fromCallable(() -> {
                    User newUser = new User();
                    String encryptedPassword = passwordEncoder.encode(password);
                    newUser.setLogin(userDTO.getLogin().toLowerCase());
                    // new user gets initially a generated password
                    newUser.setPassword(encryptedPassword);
                    newUser.setFirstName(userDTO.getFirstName());
                    newUser.setLastName(userDTO.getLastName());
                    if (userDTO.getEmail() != null) {
                        newUser.setEmail(userDTO.getEmail().toLowerCase());
                    }
                    newUser.setImageUrl(userDTO.getImageUrl());
                    newUser.setLangKey(userDTO.getLangKey());
                    // Where they came from, when an agreed surface said so. Written once here and never again:
                    // this records a fact about the past, not a property of the account.
                    newUser.setSource(source);
                    // new user is not active
                    newUser.setActivated(false);
                    // new user gets registration key
                    newUser.setActivationKey(RandomUtil.generateActivationKey());
                    return newUser;
                })
            )
            .flatMap(newUser ->
                // ROLE_PATIENT alongside ROLE_USER, not instead of it. Everything that already guards a route checks
                // ROLE_USER, so dropping it would sign every existing screen out from under them; ROLE_PATIENT is what
                // lets the portal and the gateway tell a patient from a clinician without inspecting their records.
                //
                // Note what the role does *not* do: a patient's access to their own record comes from PatientScope
                // resolving their email to a profile, and a care angel's comes from an active delegation. Neither
                // consults this. The role is for menus and for telling people apart.
                //
                // Collected rather than accumulated into a set from doOnNext: flatMap resolves its sources
                // concurrently, and two threads writing a plain HashSet is a race that shows up only under load —
                // which is exactly how it showed up, passing alone and failing in a full run.
                Flux.fromIterable(List.of(AuthoritiesConstants.USER, AuthoritiesConstants.PATIENT))
                    .concatMap(authorityRepository::findById)
                    .collect(Collectors.toCollection(HashSet<Authority>::new))
                    .map(authorities -> {
                        newUser.setAuthorities(authorities);
                        return newUser;
                    })
                    .flatMap(this::saveUser)
                    .doOnNext(user -> log.debug("Created Information for User: {}", user)));
    }

    public Mono<User> createUser(AdminUserDTO userDTO) {
        User user = new User();
        user.setLogin(userDTO.getLogin().toLowerCase());
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        if (userDTO.getEmail() != null) {
            user.setEmail(userDTO.getEmail().toLowerCase());
        }
        user.setImageUrl(userDTO.getImageUrl());
        if (userDTO.getLangKey() == null) {
            user.setLangKey(Constants.DEFAULT_LANGUAGE); // default language
        } else {
            user.setLangKey(userDTO.getLangKey());
        }
        return Flux.fromIterable(userDTO.getAuthorities() != null ? userDTO.getAuthorities() : new HashSet<>())
            .flatMap(authorityRepository::findById)
            .doOnNext(authority -> user.getAuthorities().add(authority))
            .then(Mono.just(user))
            .publishOn(Schedulers.boundedElastic())
            .map(newUser -> {
                String encryptedPassword = passwordEncoder.encode(RandomUtil.generatePassword());
                newUser.setPassword(encryptedPassword);
                newUser.setResetKey(RandomUtil.generateResetKey());
                newUser.setResetDate(Instant.now());
                newUser.setActivated(true);
                return newUser;
            })
            .flatMap(this::saveUser)
            .doOnNext(user1 -> log.debug("Created Information for User: {}", user1));
    }

    /**
     * Update all information for a specific user, and return the modified user.
     *
     * @param userDTO user to update.
     * @return updated user.
     */
    public Mono<AdminUserDTO> updateUser(AdminUserDTO userDTO) {
        return userRepository
            .findById(userDTO.getId())
            .flatMap(user -> {
                user.setLogin(userDTO.getLogin().toLowerCase());
                user.setFirstName(userDTO.getFirstName());
                user.setLastName(userDTO.getLastName());
                if (userDTO.getEmail() != null) {
                    user.setEmail(userDTO.getEmail().toLowerCase());
                }
                user.setImageUrl(userDTO.getImageUrl());
                user.setActivated(userDTO.isActivated());
                user.setLangKey(userDTO.getLangKey());
                Set<Authority> managedAuthorities = user.getAuthorities();
                managedAuthorities.clear();
                return Flux.fromIterable(userDTO.getAuthorities())
                    .flatMap(authorityRepository::findById)
                    .map(managedAuthorities::add)
                    .then(Mono.just(user));
            })
            .flatMap(this::saveUser)
            .doOnNext(user -> log.debug("Changed Information for User: {}", user))
            .map(AdminUserDTO::new);
    }

    public Mono<Void> deleteUser(String login) {
        return userRepository
            .findOneByLogin(login)
            .flatMap(user -> userRepository.delete(user).thenReturn(user))
            .doOnNext(user -> log.debug("Deleted User: {}", user))
            .then();
    }

    /**
     * Update basic information (first name, last name, email, language) for the current user.
     *
     * @param firstName first name of user.
     * @param lastName  last name of user.
     * @param email     email id of user.
     * @param langKey   language key.
     * @param imageUrl  image URL of user.
     * @return a completed {@link Mono}.
     */
    public Mono<Void> updateUser(String firstName, String lastName, String email, String langKey, String imageUrl) {
        return SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .flatMap(user -> {
                user.setFirstName(firstName);
                user.setLastName(lastName);
                if (email != null) {
                    user.setEmail(email.toLowerCase());
                }
                user.setLangKey(langKey);
                user.setImageUrl(imageUrl);
                return saveUser(user);
            })
            .doOnNext(user -> log.debug("Changed Information for User: {}", user))
            .then();
    }

    private Mono<User> saveUser(User user) {
        return SecurityUtils.getCurrentUserLogin()
            .switchIfEmpty(Mono.just(Constants.SYSTEM))
            .flatMap(login -> {
                if (user.getCreatedBy() == null) {
                    user.setCreatedBy(login);
                }
                user.setLastModifiedBy(login);
                return userRepository.save(user);
            });
    }

    public Mono<Void> changePassword(String currentClearTextPassword, String newPassword) {
        return SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .publishOn(Schedulers.boundedElastic())
            .map(user -> {
                String currentEncryptedPassword = user.getPassword();
                if (!passwordEncoder.matches(currentClearTextPassword, currentEncryptedPassword)) {
                    throw new InvalidPasswordException();
                }
                String encryptedPassword = passwordEncoder.encode(newPassword);
                user.setPassword(encryptedPassword);
                return user;
            })
            .flatMap(this::saveUser)
            .doOnNext(user -> log.debug("Changed password for User: {}", user))
            .then();
    }

    public Flux<AdminUserDTO> getAllManagedUsers(Pageable pageable) {
        return userRepository.findAllByIdNotNull(pageable).map(AdminUserDTO::new);
    }

    public Flux<UserDTO> getAllPublicUsers(Pageable pageable) {
        return userRepository.findAllByIdNotNullAndActivatedIsTrue(pageable).map(UserDTO::new);
    }

    public Mono<Long> countManagedUsers() {
        return userRepository.count();
    }

    public Mono<User> getUserWithAuthoritiesByLogin(String login) {
        return userRepository.findOneByLogin(login);
    }

    /**
     * Answers whether {@code login} can be registered, and when it cannot, offers alternatives that can.
     *
     * <p>The login is lower-cased first because {@link #registerUser} stores it lower-cased. Without
     * that, "Kojo" would be reported free while registering it fails with LoginAlreadyUsed — the
     * look-ahead has to answer the same question registration will.
     *
     * @param login the candidate, already validated against {@link Constants#LOGIN_REGEX} by the resource.
     * @return availability and, when taken, up to {@value #MAX_SUGGESTIONS} free alternatives.
     */
    public Mono<UsernameAvailabilityDTO> checkUsernameAvailability(String login) {
        // toLowerCase() with no Locale, matching registerUser above. That is deliberate and it is NOT the
        // usually-correct Locale.ROOT: what matters here is agreeing with registration, not being right in
        // isolation. Both use the default locale, so both fold "I" the same way; using Locale.ROOT in only
        // one of them would make the look-ahead and the registration disagree in a Turkish locale, where
        // "I".toLowerCase() is a dotless "ı". If this is ever moved to Locale.ROOT, move registerUser,
        // createUser and updateUser with it, and expect existing logins folded the old way to need a look.
        String normalized = login.toLowerCase();
        return isFree(normalized).flatMap(free -> {
            if (Boolean.TRUE.equals(free)) {
                return Mono.just(new UsernameAvailabilityDTO(true, List.of()));
            }
            // Concurrency 1, not the default 256. filterWhen would otherwise fire a query for every
            // candidate at once, so a single keystroke on a taken name costs ~16 round trips instead
            // of the 3-4 it takes to find three free ones. take() then cancels the rest.
            return Flux.fromIterable(suggestionsFor(normalized))
                .filterWhen(this::isFree, 1)
                .take(MAX_SUGGESTIONS)
                .collectList()
                .map(available -> new UsernameAvailabilityDTO(false, available));
        });
    }

    /**
     * Whether {@code login} (already lower-cased) would be accepted by {@link #registerUser}.
     *
     * <p>NOT simply "no row exists". registerUser deletes an existing user that has never been
     * activated and carries on, so a login held only by an abandoned registration is still
     * registrable. Reporting it taken would send someone away from a name they could have had, and
     * worse, the three-day cleanup job would free it later with no explanation — so this mirrors the
     * rule rather than the row.
     */
    private Mono<Boolean> isFree(String login) {
        return userRepository.findOneByLogin(login).map(user -> !user.isActivated()).defaultIfEmpty(true);
    }

    /**
     * Candidate alternatives for a taken login, in the order they should be offered.
     *
     * <p>Deterministic on purpose. A random suffix would make the endpoint untestable and would hand a
     * different answer to two people typing the same name at the same moment — both would be told
     * their pick is free, and the second to submit would still be rejected. Determinism does not fix
     * that race (nothing here reserves a name), but it keeps the collision visible rather than
     * scattered.
     *
     * <p>Every candidate is re-checked against {@link Constants#LOGIN_REGEX} before being offered:
     * the base is truncated to fit within 50 characters, and truncation can land mid-way through an
     * email-shaped login and leave something the registration validator would reject.
     */
    static List<String> suggestionsFor(String login) {
        List<String> candidates = new ArrayList<>();
        for (String suffix : SUGGESTION_SUFFIXES) {
            int room = MAX_LOGIN_LENGTH - suffix.length();
            String base = login.length() > room ? login.substring(0, room) : login;
            String candidate = base + suffix;
            if (!candidate.equals(login) && LOGIN_PATTERN.matcher(candidate).matches() && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    public Mono<User> getUserWithAuthorities() {
        return SecurityUtils.getCurrentUserLogin().flatMap(userRepository::findOneByLogin);
    }

    /**
     * Not activated users should be automatically deleted after 3 days.
     * <p>
     * This is scheduled to get fired everyday, at 01:00 (am).
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void removeNotActivatedUsers() {
        removeNotActivatedUsersReactively().blockLast();
    }

    public Flux<User> removeNotActivatedUsersReactively() {
        return userRepository
            .findAllByActivatedIsFalseAndActivationKeyIsNotNullAndCreatedDateBefore(Instant.now().minus(3, ChronoUnit.DAYS))
            .flatMap(user -> userRepository.delete(user).thenReturn(user))
            .doOnNext(user -> log.debug("Deleted User: {}", user));
    }

    /**
     * Gets a list of all the authorities.
     * @return a list of all the authorities.
     */
    public Flux<String> getAuthorities() {
        return authorityRepository.findAll().map(Authority::getName);
    }
}
