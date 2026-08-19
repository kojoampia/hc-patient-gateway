package net.jojoaddison.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.jojoaddison.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.jhipster.config.JHipsterProperties;

/**
 * Service for sending emails asynchronously.
 */
@Service
public class MailService {

    private final Logger log = LoggerFactory.getLogger(MailService.class);

    private static final String USER = "user";

    private static final String BASE_URL = "baseUrl";

    private final JHipsterProperties jHipsterProperties;

    private final JavaMailSender javaMailSender;

    private final MessageSource messageSource;

    private final SpringTemplateEngine templateEngine;

    public MailService(
        JHipsterProperties jHipsterProperties,
        JavaMailSender javaMailSender,
        MessageSource messageSource,
        SpringTemplateEngine templateEngine
    ) {
        this.jHipsterProperties = jHipsterProperties;
        this.javaMailSender = javaMailSender;
        this.messageSource = messageSource;
        this.templateEngine = templateEngine;
    }

    /**
     * Sends an email off the calling thread and returns immediately.
     *
     * <p>The {@code subscribeOn} is the whole point of this method and must not be removed. JavaMail is blocking, and
     * every caller here is a reactive handler running on a Netty event loop; without it the {@code Mono.defer} below
     * runs the SMTP conversation on the subscribing thread — the event loop — for as long as the relay takes. Measured
     * against a real relay from production: <strong>2.8 seconds on an event loop thread</strong>, during which every
     * other request assigned to that thread waits. It reads as asynchronous and is not.</p>
     *
     * <p>BlockHound does not catch this. It fails blocking calls on non-blocking threads, but {@code MailServiceIT}
     * mocks {@link JavaMailSender}, so no socket is ever opened during the tests and nothing blocks.
     * {@code testSendEmailRunsOffTheCallingThread} guards it instead.</p>
     */
    public void sendEmail(String to, String subject, String content, boolean isMultipart, boolean isHtml) {
        Mono.defer(() -> {
            this.sendEmailSync(to, subject, content, isMultipart, isHtml);
            return Mono.empty();
        })
            .subscribeOn(Schedulers.boundedElastic())
            // Fire-and-forget, so nothing downstream would ever see a failure: sendEmailSync already logs the mail
            // failures it expects, and this handles the rest rather than letting Reactor drop them silently.
            .subscribe(null, e -> log.warn("Email to '{}' failed unexpectedly", to, e));
    }

    private void sendEmailSync(String to, String subject, String content, boolean isMultipart, boolean isHtml) {
        log.debug(
            "Send email[multipart '{}' and html '{}'] to '{}' with subject '{}' and content={}",
            isMultipart,
            isHtml,
            to,
            subject,
            content
        );

        // Prepare message using a Spring helper
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper message = new MimeMessageHelper(mimeMessage, isMultipart, StandardCharsets.UTF_8.name());
            message.setTo(to);
            message.setFrom(jHipsterProperties.getMail().getFrom());
            message.setSubject(subject);
            message.setText(content, isHtml);
            javaMailSender.send(mimeMessage);
            // INFO, not DEBUG. Production runs at INFO, so a successful send used to log NOTHING,
            // and the only asymmetry in the pair below was that failure was visible and success was
            // not. That is precisely how outbound mail could be broken from 2026-08-07 to 08-08
            // without anyone noticing: the `mail` health indicator went DOWN silently, and there was
            // no positive signal anywhere to be missing. The first message this stack is known to
            // have delivered was confirmed on 2026-08-11 by reading the recipient's inbox, because
            // the logs could not answer it. They can now.
            log.info("Sent email to User '{}'", to);
        } catch (MailException | MessagingException e) {
            log.warn("Email could not be sent to user '{}'", to, e);
        }
    }

    /**
     * Renders a template and sends it off the calling thread. See {@link #sendEmail} for why the scheduler matters:
     * this path additionally renders Thymeleaf and resolves a message bundle, so it does even more work than the send
     * itself before it reaches the relay.
     */
    public void sendEmailFromTemplate(User user, String templateName, String titleKey) {
        Mono.defer(() -> {
            this.sendEmailFromTemplateSync(user, templateName, titleKey);
            return Mono.empty();
        })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(null, e -> log.warn("Email to '{}' failed unexpectedly", user.getEmail(), e));
    }

    private void sendEmailFromTemplateSync(User user, String templateName, String titleKey) {
        if (user.getEmail() == null) {
            log.debug("Email doesn't exist for user '{}'", user.getLogin());
            return;
        }
        Locale locale = Locale.forLanguageTag(user.getLangKey());
        Context context = new Context(locale);
        context.setVariable(USER, user);
        context.setVariable(BASE_URL, jHipsterProperties.getMail().getBaseUrl());
        String content = templateEngine.process(templateName, context);
        String subject = messageSource.getMessage(titleKey, null, locale);
        this.sendEmailSync(user.getEmail(), subject, content, false, true);
    }

    public void sendActivationEmail(User user) {
        log.debug("Sending activation email to '{}'", user.getEmail());
        this.sendEmailFromTemplate(user, "mail/activationEmail", "email.activation.title");
    }

    public void sendCreationEmail(User user) {
        log.debug("Sending creation email to '{}'", user.getEmail());
        this.sendEmailFromTemplate(user, "mail/creationEmail", "email.activation.title");
    }

    public void sendPasswordResetMail(User user) {
        log.debug("Sending password reset email to '{}'", user.getEmail());
        this.sendEmailFromTemplate(user, "mail/passwordResetEmail", "email.reset.title");
    }

    /**
     * Invites a newly created care-angel account to set a password.
     *
     * <p>Doubles as the nomination and the way in. The account was created already activated with a password nobody
     * knows, so this link is the only route to it — which is what makes "cannot authenticate until they set a
     * password" true without a new flag anywhere.</p>
     */
    public void sendCareAngelNominationMail(User user) {
        log.debug("Sending care angel nomination email to '{}'", user.getEmail());
        this.sendEmailFromTemplate(user, "mail/careAngelNominationEmail", "email.careangel.title");
    }

    /** Tells somebody who already has an account that they have been nominated. No reset link: they have a password. */
    public void sendCareAngelNominationToExistingUserMail(User user) {
        log.debug("Sending care angel nomination email to existing user '{}'", user.getEmail());
        this.sendEmailFromTemplate(user, "mail/careAngelInviteExistingEmail", "email.careangel.title");
    }

    /**
     * Tells a patient their care angel has stepped down.
     *
     * <p>The one delegation mail that is not a courtesy. A patient who silently has nobody able to act for them is
     * exactly the person the arrangement exists to protect, and only they can nominate a replacement.</p>
     */
    public void sendCareAngelSteppedDownMail(User user) {
        log.debug("Sending care angel stepped-down email to '{}'", user.getEmail());
        this.sendEmailFromTemplate(user, "mail/careAngelSteppedDownEmail", "email.delegation.ended.title");
    }

    /** Tells an angel their access has ended, whoever ended it. */
    public void sendCareAngelAccessEndedMail(User user) {
        log.debug("Sending care angel access-ended email to '{}'", user.getEmail());
        this.sendEmailFromTemplate(user, "mail/careAngelAccessEndedEmail", "email.delegation.revoked.title");
    }
}
