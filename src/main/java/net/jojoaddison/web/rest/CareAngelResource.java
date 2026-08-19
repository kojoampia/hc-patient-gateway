package net.jojoaddison.web.rest;

import java.util.Map;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.CareAngelService;
import net.jojoaddison.service.MailService;
import net.jojoaddison.service.dto.CareAngelAccountDTO;
import net.jojoaddison.service.event.PatientEventPublisher;
import net.jojoaddison.service.event.PatientEventType;
import net.jojoaddison.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Creates the account a patient's nominated care angel will sign in with.
 *
 * <p>This is the one endpoint that exists because only the gateway can create a user. The delegation itself — the
 * thing that actually grants access — lives in the patient service and is created separately; this returns the login
 * that profile records for display, and sends the invitation.</p>
 *
 * <p>Authenticated as the <em>patient</em>. That is what makes it safe to expose at all: a caller can only ever cause
 * an account to be created for somebody they are nominating, and the account so created can see nothing until its
 * holder accepts the delegation.</p>
 */
@RestController
@RequestMapping("/api/care-angels")
public class CareAngelResource {

    private static final String ENTITY_NAME = "careAngel";

    private final Logger log = LoggerFactory.getLogger(CareAngelResource.class);

    private final CareAngelService careAngelService;
    private final MailService mailService;
    private final UserRepository userRepository;
    private final PatientEventPublisher events;

    public CareAngelResource(
        CareAngelService careAngelService,
        MailService mailService,
        UserRepository userRepository,
        PatientEventPublisher events
    ) {
        this.careAngelService = careAngelService;
        this.mailService = mailService;
        this.userRepository = userRepository;
        this.events = events;
    }

    /**
     * {@code POST /api/care-angels} : find or create the nominee's account and invite them.
     *
     * @param request the nominee's details.
     * @return the login and email, and whether the account already existed. <strong>Never the reset key</strong> —
     *         that goes to the nominee's inbox and nowhere else, or the patient who nominated them could set their
     *         password and sign in as them.
     */
    @PostMapping("")
    public Mono<CareAngelAccountDTO> nominate(@RequestBody CareAngelService.CareAngelRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new BadRequestAlertException("A care angel needs an email address", ENTITY_NAME, "emailrequired");
        }

        return SecurityUtils.getCurrentUserLogin()
            .flatMap(login -> userRepository.findOneByLogin(login))
            .switchIfEmpty(Mono.error(new BadRequestAlertException("No signed-in account", ENTITY_NAME, "nocaller")))
            .flatMap(patient -> {
                if (patient.getEmail() != null && patient.getEmail().equalsIgnoreCase(request.email().trim())) {
                    // Nobody is their own angel. Beyond being nonsense, it would create a delegation whose resolution
                    // is undefined — the caller resolves to themselves first, so the row would sit there looking like
                    // it granted something and granting nothing.
                    return Mono.error(new BadRequestAlertException("A patient cannot nominate themselves", ENTITY_NAME, "selfnomination"));
                }
                return careAngelService.findOrCreate(request);
            })
            .flatMap(account ->
                userRepository
                    .findOneByEmailIgnoreCase(account.email())
                    .doOnNext(angel -> {
                        if (account.accountExisted()) {
                            mailService.sendCareAngelNominationToExistingUserMail(angel);
                        } else {
                            mailService.sendCareAngelNominationMail(angel);
                            events.publish(
                                PatientEventType.ACCOUNT_CREATED,
                                angel.getEmail(),
                                angel.getLogin(),
                                Map.of("authorities", "ROLE_USER,ROLE_ANGEL", "activated", true, "reason", "careAngelNomination")
                            );
                            // Created already activated, so the two events are simultaneous rather than minutes apart.
                            // A consumer building a funnel needs to see the activation it would otherwise wait for.
                            events.publish(
                                PatientEventType.ACCOUNT_ACTIVATED,
                                angel.getEmail(),
                                angel.getLogin(),
                                Map.of("activatedAt", java.time.Instant.now().toString())
                            );
                        }
                        log.debug("Nominated care angel {} (existing account: {})", angel.getLogin(), account.accountExisted());
                    })
                    // The reset key never leaves the server: strip it before this reaches the patient.
                    .thenReturn(new CareAngelAccountDTO(account.login(), account.email(), account.accountExisted(), null)));
    }
}
