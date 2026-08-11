package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.config.Constants;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tech.jhipster.config.JHipsterProperties;

/**
 * Integration tests for {@link MailService}.
 */
@IntegrationTest
class MailServiceIT {

    private static final String[] languages = {
        // jhipster-needle-i18n-language-constant - JHipster will add/remove languages in this array
    };
    /**
     * Mail is sent on {@code Schedulers.boundedElastic()} rather than the caller's thread, so every verification here
     * has to wait for it instead of asserting immediately. Generous: this only costs time when a test is failing.
     */
    private static final long SEND_TIMEOUT_MS = 5_000;

    private static final Pattern PATTERN_LOCALE_3 = Pattern.compile("([a-z]{2})-([a-zA-Z]{4})-([a-z]{2})");
    private static final Pattern PATTERN_LOCALE_2 = Pattern.compile("([a-z]{2})-([a-z]{2})");

    @Autowired
    private JHipsterProperties jHipsterProperties;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @Captor
    private ArgumentCaptor<MimeMessage> messageCaptor;

    @Autowired
    private MailService mailService;

    @BeforeEach
    public void setup() {
        // Spring Boot 4 no longer initialises @Captor fields as a side effect of the mock-bean support.
        MockitoAnnotations.openMocks(this);
        doNothing().when(javaMailSender).send(any(MimeMessage.class));
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    void testSendEmail() throws Exception {
        mailService.sendEmail("john.doe@example.com", "testSubject", "testContent", false, false);
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        assertThat(message.getSubject()).isEqualTo("testSubject");
        assertThat(message.getAllRecipients()[0]).hasToString("john.doe@example.com");
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent()).isInstanceOf(String.class);
        assertThat(message.getContent()).hasToString("testContent");
        assertThat(message.getDataHandler().getContentType()).isEqualTo("text/plain; charset=UTF-8");
    }

    @Test
    void testSendHtmlEmail() throws Exception {
        mailService.sendEmail("john.doe@example.com", "testSubject", "testContent", false, true);
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        assertThat(message.getSubject()).isEqualTo("testSubject");
        assertThat(message.getAllRecipients()[0]).hasToString("john.doe@example.com");
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent()).isInstanceOf(String.class);
        assertThat(message.getContent()).hasToString("testContent");
        assertThat(message.getDataHandler().getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void testSendMultipartEmail() throws Exception {
        mailService.sendEmail("john.doe@example.com", "testSubject", "testContent", true, false);
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        MimeMultipart mp = (MimeMultipart) message.getContent();
        MimeBodyPart part = (MimeBodyPart) ((MimeMultipart) mp.getBodyPart(0).getContent()).getBodyPart(0);
        ByteArrayOutputStream aos = new ByteArrayOutputStream();
        part.writeTo(aos);
        assertThat(message.getSubject()).isEqualTo("testSubject");
        assertThat(message.getAllRecipients()[0]).hasToString("john.doe@example.com");
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent()).isInstanceOf(Multipart.class);
        assertThat(aos).hasToString("\r\ntestContent");
        assertThat(part.getDataHandler().getContentType()).isEqualTo("text/plain; charset=UTF-8");
    }

    @Test
    void testSendMultipartHtmlEmail() throws Exception {
        mailService.sendEmail("john.doe@example.com", "testSubject", "testContent", true, true);
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        MimeMultipart mp = (MimeMultipart) message.getContent();
        MimeBodyPart part = (MimeBodyPart) ((MimeMultipart) mp.getBodyPart(0).getContent()).getBodyPart(0);
        ByteArrayOutputStream aos = new ByteArrayOutputStream();
        part.writeTo(aos);
        assertThat(message.getSubject()).isEqualTo("testSubject");
        assertThat(message.getAllRecipients()[0]).hasToString("john.doe@example.com");
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent()).isInstanceOf(Multipart.class);
        assertThat(aos).hasToString("\r\ntestContent");
        assertThat(part.getDataHandler().getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void testSendEmailFromTemplate() throws Exception {
        User user = new User();
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setLogin("john");
        user.setEmail("john.doe@example.com");
        mailService.sendEmailFromTemplate(user, "mail/testEmail", "email.test.title");
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        assertThat(message.getSubject()).isEqualTo("test title");
        assertThat(message.getAllRecipients()[0]).hasToString(user.getEmail());
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent().toString()).isEqualToNormalizingNewlines("<html>test title, http://127.0.0.1:8080, john</html>\n");
        assertThat(message.getDataHandler().getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void testSendActivationEmail() throws Exception {
        User user = new User();
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setLogin("john");
        user.setEmail("john.doe@example.com");
        mailService.sendActivationEmail(user);
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        assertThat(message.getAllRecipients()[0]).hasToString(user.getEmail());
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent().toString()).isNotEmpty();
        assertThat(message.getDataHandler().getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    /**
     * The wording that actually reaches users, asserted against src/main/resources — NOT by rendering
     * a message.
     *
     * <p>Rendering cannot check this. src/test/resources/i18n/ deliberately shadows the real bundles
     * on the test classpath (its own comment says "this file is loaded instead of real file", and
     * testSendLocalizedEmailForAllSupportedLanguages depends on that), so every mail rendered in this
     * class uses test wording. Production could say anything at all and no rendering test would
     * notice — which is how these bundles kept hc-admin's branding until a delivered message was read
     * by hand on 2026-08-11.
     */
    private Properties productionBundle(String suffix) throws Exception {
        Path file = Path.of("src/main/resources/i18n/messages" + suffix + ".properties");
        assertThat(file).as("production bundle %s", file).exists();
        Properties properties = new Properties();
        // Explicit UTF-8, matching spring.messages.encoding. Reading it any other way would mask the
        // very defect the German assertion below exists to catch.
        properties.load(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
        return properties;
    }

    @Test
    void everyProductionBundleCarriesThisProductsBrand() throws Exception {
        for (String suffix : new String[] { "", "_en", "_fr", "_de" }) {
            Properties bundle = productionBundle(suffix);
            assertThat(bundle.stringPropertyNames()).contains("email.activation.title", "email.activation.text1", "email.signature");
            for (String key : bundle.stringPropertyNames()) {
                String value = bundle.getProperty(key);
                assertThat(value)
                    .as("messages%s.properties -> %s", suffix, key)
                    // "Admin" told every patient their Admin account had been created; "patientGateway"
                    // and "JHipster" are the generator's placeholders. None belongs in a user's inbox.
                    .doesNotContain("Admin")
                    .doesNotContain("patientGateway")
                    .doesNotContain("JHipster");
            }
            assertThat(bundle.getProperty("email.activation.title")).contains("Abofonsa BridgeCare");
            assertThat(bundle.getProperty("email.activation.text1")).contains("Abofonsa BridgeCare");
        }
    }

    @Test
    void theGermanBundleIsUtf8AndKeepsItsUmlauts() throws Exception {
        // messages_de.properties was ISO-8859-1 while spring.messages.encoding defaults to UTF-8, so
        // German mail went out reading "Liebe Gr??e" and "zur?cksetzen". Nothing failed: the file
        // parsed, the send succeeded, and the damage was visible only to a German-speaking recipient.
        // U+FFFD is what a mis-decoded byte becomes, so asserting on its ABSENCE is what survives
        // someone re-saving the file in the wrong encoding.
        Properties german = productionBundle("_de");

        for (String key : german.stringPropertyNames()) {
            assertThat(german.getProperty(key)).as("messages_de.properties -> %s", key).doesNotContain("\uFFFD");
        }
        assertThat(german.getProperty("email.activation.text2")).isEqualTo("Liebe Gr\u00fc\u00dfe,");
        assertThat(german.getProperty("email.reset.title")).contains("zur\u00fccksetzen");
    }

    @Test
    void testCreationEmail() throws Exception {
        User user = new User();
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setLogin("john");
        user.setEmail("john.doe@example.com");
        mailService.sendCreationEmail(user);
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        assertThat(message.getAllRecipients()[0]).hasToString(user.getEmail());
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent().toString()).isNotEmpty();
        assertThat(message.getDataHandler().getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void testSendPasswordResetMail() throws Exception {
        User user = new User();
        user.setLangKey(Constants.DEFAULT_LANGUAGE);
        user.setLogin("john");
        user.setEmail("john.doe@example.com");
        mailService.sendPasswordResetMail(user);
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(messageCaptor.capture());
        MimeMessage message = messageCaptor.getValue();
        assertThat(message.getAllRecipients()[0]).hasToString(user.getEmail());
        assertThat(message.getFrom()[0]).hasToString(jHipsterProperties.getMail().getFrom());
        assertThat(message.getContent().toString()).isNotEmpty();
        assertThat(message.getDataHandler().getContentType()).isEqualTo("text/html;charset=UTF-8");
    }

    @Test
    void testSendEmailWithException() {
        doThrow(MailSendException.class).when(javaMailSender).send(any(MimeMessage.class));
        try {
            mailService.sendEmail("john.doe@example.com", "testSubject", "testContent", false, false);
        } catch (Exception e) {
            fail("Exception shouldn't have been thrown");
        }
        // Wait for the attempt: the send is asynchronous now, so returning without throwing proves nothing on its own
        // — the failure happens on another thread and must stay contained there.
        verify(javaMailSender, timeout(SEND_TIMEOUT_MS)).send(any(MimeMessage.class));
    }

    /**
     * Guards the reason {@link MailService} schedules its work on {@code boundedElastic}.
     *
     * <p>Every caller is a reactive handler on a Netty event loop, and JavaMail is blocking. Before this was fixed the
     * SMTP conversation ran on the subscribing thread: against a real relay that was measured at 2.8 seconds on an
     * event loop thread in production, blocking every other request on it. The code looked asynchronous — the work was
     * wrapped in {@code Mono.defer(...).subscribe()} — but without a scheduler that runs inline.</p>
     *
     * <p>BlockHound cannot catch this here, because {@code javaMailSender} is mocked and never touches a socket, so
     * this asserts the property directly: the send must not happen on the thread that asked for it.</p>
     */
    @Test
    void testSendEmailRunsOffTheCallingThread() throws InterruptedException {
        AtomicReference<String> sendingThread = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            sent.countDown();
            return null;
        })
            .when(javaMailSender)
            .send(any(MimeMessage.class));

        String callingThread = Thread.currentThread().getName();
        mailService.sendEmail("john.doe@example.com", "testSubject", "testContent", false, false);

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("the email was never sent").isTrue();
        assertThat(sendingThread.get()).isNotEqualTo(callingThread);
        assertThat(sendingThread.get()).startsWith("boundedElastic-");
    }

    @Test
    void testSendLocalizedEmailForAllSupportedLanguages() throws Exception {
        User user = new User();
        user.setLogin("john");
        user.setEmail("john.doe@example.com");
        for (String langKey : languages) {
            user.setLangKey(langKey);
            mailService.sendEmailFromTemplate(user, "mail/testEmail", "email.test.title");
            verify(javaMailSender, timeout(SEND_TIMEOUT_MS).atLeastOnce()).send(messageCaptor.capture());
            MimeMessage message = messageCaptor.getValue();

            String propertyFilePath = "i18n/messages_" + getMessageSourceSuffixForLanguage(langKey) + ".properties";
            URL resource = this.getClass().getClassLoader().getResource(propertyFilePath);
            Path file = Path.of(new URI(resource.getFile()).getPath());
            Properties properties = new Properties();
            properties.load(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));

            String emailTitle = (String) properties.get("email.test.title");
            assertThat(message.getSubject()).isEqualTo(emailTitle);
            assertThat(message.getContent().toString()).isEqualToNormalizingNewlines(
                "<html>" + emailTitle + ", http://127.0.0.1:8080, john</html>\n"
            );
        }
    }

    /**
     * Convert a lang key to the Java locale.
     */
    private String getMessageSourceSuffixForLanguage(String langKey) {
        String javaLangKey = langKey;
        Matcher matcher2 = PATTERN_LOCALE_2.matcher(langKey);
        if (matcher2.matches()) {
            javaLangKey = matcher2.group(1) + "_" + matcher2.group(2).toUpperCase();
        }
        Matcher matcher3 = PATTERN_LOCALE_3.matcher(langKey);
        if (matcher3.matches()) {
            javaLangKey = matcher3.group(1) + "_" + matcher3.group(2) + "_" + matcher3.group(3).toUpperCase();
        }
        return javaLangKey;
    }
}
