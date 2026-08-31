package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * That the four deletion mails actually render.
 *
 * <p><b>The unit tests for {@link net.jojoaddison.service.event.DeletionRequestMailer} cannot catch what this
 * catches.</b> They mock {@code MailService} entirely, so they prove the right method is called and nothing about
 * what it produces. A Thymeleaf template naming a message key that does not exist renders {@code ??key??} into the
 * body and sends it — no exception, no log, a delivered email with a placeholder where a sentence should be.</p>
 *
 * <p>These mails are the ones where that matters most. After a completed erasure the portal has nothing left to show
 * the patient, so the mail is the only account they get of what happened to their record.</p>
 *
 * <p>Fifteen keys went into four bundles at once ({@code messages.properties} and the three language files), which is
 * exactly the shape of change where one gets missed in one file.</p>
 */
@IntegrationTest
class DeletionMailRenderingIT {

    private static final long SEND_TIMEOUT_MS = 5_000;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @Captor
    private ArgumentCaptor<MimeMessage> messageCaptor;

    @Autowired
    private MailService mailService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    private User patient(String langKey) {
        User user = new User();
        user.setLangKey(langKey);
        user.setLogin("kojo");
        user.setEmail("kojo@example.test");
        return user;
    }

    private String bodyOf() throws Exception {
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        return messageCaptor.getValue().getContent().toString();
    }

    private String subjectOf() throws Exception {
        return messageCaptor.getValue().getSubject();
    }

    /** No unresolved key, in a body or a subject, ever. */
    private void assertFullyResolved(String text) {
        assertThat(text).as("an unresolved message key renders as ??key?? and sends anyway").doesNotContain("??");
    }

    @Test
    void theRequestedMailStatesTheDate() throws Exception {
        mailService.sendDeletionRequestedMail(patient(Constants.DEFAULT_LANGUAGE), "14 September 2026");

        String body = bodyOf();
        assertFullyResolved(body);
        assertFullyResolved(subjectOf());
        // The whole reason this mail takes a template variable: the date has to survive into the body.
        assertThat(body).contains("14 September 2026").contains("kojo");
    }

    @Test
    void theWithdrawnMailSaysNothingWasErased() throws Exception {
        mailService.sendDeletionWithdrawnMail(patient(Constants.DEFAULT_LANGUAGE));

        String body = bodyOf();
        assertFullyResolved(body);
        assertFullyResolved(subjectOf());
        assertThat(body).contains("kojo");
    }

    @Test
    void theCompletedMailDoesNotSendThePatientBackIntoAnEmptyPortal() throws Exception {
        mailService.sendDeletionCompletedMail(patient(Constants.DEFAULT_LANGUAGE));

        String body = bodyOf();
        assertFullyResolved(body);
        assertFullyResolved(subjectOf());
        // By the time this arrives there is no record to show. A link into the app would land on an empty
        // screen, which is a worse answer than none — so the template deliberately carries no anchor.
        assertThat(body).doesNotContain("<a ");
    }

    @Test
    void theRefusedMailDoesNotCarryTheReason() throws Exception {
        mailService.sendDeletionRefusedMail(patient(Constants.DEFAULT_LANGUAGE));

        String body = bodyOf();
        assertFullyResolved(body);
        assertFullyResolved(subjectOf());
        // The administrator's words stay in the portal, which is authenticated. This mail points at it.
        assertThat(body).contains("delete-account");
    }

    @ParameterizedTest
    @ValueSource(strings = { "en", "fr", "de" })
    void everyLocaleResolvesEveryKey(String langKey) throws Exception {
        // Fifteen keys across four bundles: one missed in one file is the likely mistake, and it shows up
        // only in the language nobody on the team is reading.
        mailService.sendDeletionCompletedMail(patient(langKey));

        assertFullyResolved(bodyOf());
        assertFullyResolved(subjectOf());
    }
}
