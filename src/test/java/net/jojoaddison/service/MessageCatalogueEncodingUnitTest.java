package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * That every shipped message catalogue round-trips its accents through the encoding the application configures.
 *
 * <p><b>The hazard is the bytes on disk, not the setting.</b> {@code spring.messages.encoding} is belt and braces:
 * Boot's {@code MessageSourceProperties} already initialises it to UTF-8, so a catalogue saved wrongly is decoded
 * wrongly whatever that property says. hc-admin's actual defect was a <i>double-encoded</i> German catalogue — bytes
 * that are valid UTF-8, parse without complaint, and render {@code Ihr LÃ¶schantrag} where a patient should read
 * {@code Ihr Löschantrag}. No encoding setting prevents that, and no test asserting the setting is present would have
 * seen it.</p>
 *
 * <p><b>Why this is a unit test and not an integration test, which is the trap here.</b> Resolving these keys through
 * the autowired {@code MessageSource} would assert nothing about what ships: {@code src/test/resources/i18n/}
 * deliberately shadows the real bundles on the test classpath — its own comment says <i>"this file is loaded instead
 * of real file"</i> — so every mail rendered under {@code @IntegrationTest} carries test wording. This test therefore
 * builds the same {@link ResourceBundleMessageSource} Boot's {@code MessageSourceAutoConfiguration} builds, over a
 * classloader rooted at {@code src/main/resources} alone, with the basename and encoding <i>read out of the shipped
 * {@code application.yml}</i> rather than restated here. Change the configuration and this test follows it; point it
 * at ISO-8859-1 and it goes red.</p>
 *
 * <p><b>Reach is derived, never listed.</b> The catalogues are discovered by listing the bundle directory, and the
 * keys are every key whose value carries a non-ASCII character. Adding {@code messages_es.properties}, or a first
 * accent to an existing value, enrols it with nobody remembering to. That matters because the exposure is not
 * uniform: several of these values are <i>subject lines</i> on account-deletion and care-delegation mail, which is
 * the last thing a patient hears about their health record.</p>
 *
 * <p>{@code MailServiceIT#theGermanBundleIsUtf8AndKeepsItsUmlauts} is the narrower ancestor of this test and stays
 * as it is. It reads the German file directly and asserts two named values plus the absence of U+FFFD, so it catches
 * a file re-saved as Latin-1 and cannot catch double-encoding of any other key — including all fifteen
 * {@code email.deletion.*} keys.</p>
 */
class MessageCatalogueEncodingUnitTest {

    /** Where the catalogues actually ship from. The test classpath copy is a fixture and is deliberately not read. */
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    private static final Path APPLICATION_YML = MAIN_RESOURCES.resolve("config/application.yml");

    /**
     * The two ways a UTF-8 file gets double-encoded: read back as Latin-1, or as the Windows code page an editor
     * defaults to. They differ over the bytes 0x80-0x9F, which is where {@code ß} lives, so checking one flavour
     * would miss half the German alphabet's worth of cases.
     */
    private static final Charset[] MOJIBAKE_FLAVOURS = { StandardCharsets.ISO_8859_1, Charset.forName("windows-1252") };

    /** U+FFFD is what a byte that could not be decoded becomes. It is never anybody's translation. */
    private static final char REPLACEMENT = '�';

    private static String basename;
    private static String configuredEncoding;
    private static ResourceBundleMessageSource messageSource;

    /** Locale-tag (empty for the default bundle) to the values that bundle declares, as decoded from its own bytes. */
    private static Map<String, Properties> catalogues;

    @BeforeAll
    static void readTheShippedConfigurationAndCatalogues() throws IOException {
        Properties configuration = flatten(APPLICATION_YML);
        basename = configuration.getProperty("spring.messages.basename");
        assertThat(basename).as("spring.messages.basename in %s", APPLICATION_YML).isNotBlank();

        // Read, not asserted. The property is optional: absent, Boot's own default is UTF-8 and the catalogues are
        // still correct, so demanding its presence would be a guard that passes forever and protects nothing. What
        // is worth catching is somebody setting it to something else, and using the value does exactly that.
        configuredEncoding = configuration.getProperty("spring.messages.encoding");

        messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename(basename);
        if (configuredEncoding != null) {
            messageSource.setDefaultEncoding(configuredEncoding);
        }
        // Deliberately pinned rather than left on Boot's default, which falls back to the machine's locale: that
        // would make which file answers a missing key depend on whose box the suite runs on. ROOT leaves only the
        // bundle asked for and the default bundle, which can make this test stricter and never laxer.
        messageSource.setDefaultLocale(Locale.ROOT);
        messageSource.setBundleClassLoader(classLoaderOverMainResourcesOnly());

        catalogues = discoverCatalogues();
    }

    /* --------------------------------------------------------------------------------------------------------- */

    @Test
    void thereIsSomethingToCheckAndItIsWhatShips() {
        // A sweep that silently found nothing would pass every assertion below it. Assert the instrument's reach
        // first, structurally: at least one catalogue, and among them at least one subject line carrying an accent,
        // which is the value class this whole item is about.
        assertThat(catalogues).as("catalogues discovered under %s/%s", MAIN_RESOURCES, basename).isNotEmpty();

        List<String> accentedSubjectLines = new ArrayList<>();
        catalogues.forEach(
            (tag, bundle) ->
                accentedKeys(bundle)
                    .stream()
                    .filter(key -> key.endsWith(".title"))
                    .forEach(key -> accentedSubjectLines.add(tag + ':' + key))
        );
        assertThat(accentedSubjectLines)
            .as("accented subject lines — the values that reach an inbox as a mail's Subject header")
            .isNotEmpty();
    }

    @Test
    void everyCatalogueIsValidUtf8OnDisk() throws IOException {
        for (Path file : catalogueFiles()) {
            assertThatCode(() -> strictUtf8(Files.readAllBytes(file)))
                .as("%s is not decodable as UTF-8 — it was saved in another encoding", file)
                .doesNotThrowAnyException();
        }
    }

    @Test
    void noCatalogueValueIsDoubleEncoded() {
        catalogues.forEach((tag, bundle) -> {
            Locale locale = localeOf(tag);
            for (String key : accentedKeys(bundle)) {
                String resolved = messageSource.getMessage(key, null, locale);
                assertThat(isDoubleEncoded(resolved))
                    .as(
                        "messages%s.properties -> %s is double-encoded: %s (reads as %s once, should read as it was written)",
                        suffixOf(tag),
                        key,
                        resolved,
                        reinterpret(resolved)
                    )
                    .isFalse();
                assertThat(resolved)
                    .as("messages%s.properties -> %s contains U+FFFD, so a byte could not be decoded", suffixOf(tag), key)
                    .doesNotContain(String.valueOf(REPLACEMENT));
            }
        });
    }

    @Test
    void everyValueSurvivesTheConfiguredEncodingUnchanged() {
        // The round trip proper: the characters the file declares are the characters the application resolves.
        // A mismatch means the MessageSource is reading these bytes through some other charset.
        catalogues.forEach((tag, bundle) -> {
            Locale locale = localeOf(tag);
            for (String key : new TreeSet<>(bundle.stringPropertyNames())) {
                assertThat(messageSource.getMessage(key, null, locale))
                    .as("messages%s.properties -> %s does not survive spring.messages.encoding=%s", suffixOf(tag), key, configuredEncoding)
                    .isEqualTo(bundle.getProperty(key));
            }
        });
    }

    @Test
    void anAccentedSubjectSurvivesTheMailHeaderToo() throws Exception {
        // The last leg of MailService's chain: messageSource.getMessage(titleKey, ...) -> setSubject(subject). A
        // Subject header is RFC 2047 encoded words rather than raw bytes, so it is its own opportunity to lose an
        // umlaut, and MailServiceIT only ever puts the ASCII literal "testSubject" through it.
        for (Map.Entry<String, Properties> catalogue : catalogues.entrySet()) {
            for (String key : accentedKeys(catalogue.getValue())) {
                if (!key.endsWith(".title")) {
                    continue;
                }
                String subject = messageSource.getMessage(key, null, localeOf(catalogue.getKey()));
                MimeMessage mimeMessage = new MimeMessage((Session) null);
                new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name()).setSubject(subject);
                assertThat(mimeMessage.getSubject())
                    .as("messages%s.properties -> %s does not survive the Subject header", suffixOf(catalogue.getKey()), key)
                    .isEqualTo(subject);
            }
        }
    }

    @Test
    void theDetectorCanProduceAPositiveOnThisProductsOwnWording() {
        // A negative result means nothing until the probe has been shown to produce a positive one. Take a value
        // that really ships, corrupt it exactly the way hc-admin's catalogue was corrupted, and watch the detector
        // above say so — without a corrupted file ever existing on disk.
        String genuine = anyAccentedValue();

        assertThat(isDoubleEncoded(genuine)).as("the detector must not flag correct wording: %s", genuine).isFalse();
        assertThat(isDoubleEncoded(doubleEncode(genuine))).as("the detector missed a double-encoded %s", genuine).isTrue();
        assertThat(isDoubleEncoded("plain ascii with no accent in it")).isFalse();
        assertThat(doubleEncode(genuine)).isNotEqualTo(genuine);
    }

    /* --------------------------------------------------------------------------------------------------------- */

    /**
     * True when {@code value} is UTF-8 bytes that were decoded as a single-byte charset and then re-saved. Such a
     * string re-encodes to those original bytes and decodes cleanly as UTF-8 a second time, which correctly encoded
     * text does not: a lone accented letter is never a valid multi-byte sequence.
     */
    private static boolean isDoubleEncoded(String value) {
        return !reinterpret(value).equals(value);
    }

    /** What {@code value} would read as if it were double-encoded, or {@code value} itself when it is not. */
    private static String reinterpret(String value) {
        for (Charset flavour : MOJIBAKE_FLAVOURS) {
            try {
                CharsetEncoder encoder = flavour
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
                return strictUtf8(encoder.encode(CharBuffer.wrap(value)));
            } catch (CharacterCodingException notThisFlavour) {
                // Either the text does not fit the flavour, or the bytes are not UTF-8 -- both mean it did not come
                // through this one. Try the next.
            }
        }
        return value;
    }

    /** The corruption itself, so the detector is exercised against a value this product really sends. */
    private static String doubleEncode(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return strictUtf8(ByteBuffer.wrap(bytes));
    }

    private static String strictUtf8(ByteBuffer bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(bytes)
            .toString();
    }

    /* --------------------------------------------------------------------------------------------------------- */

    private static Properties flatten(Path yaml) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(yaml));
        Properties properties = factory.getObject();
        assertThat(properties).as("%s", yaml).isNotNull();
        return properties;
    }

    private static URLClassLoader classLoaderOverMainResourcesOnly() {
        try {
            // A null parent is the point: with the ordinary test classloader, src/test/resources/i18n would answer
            // first and this test would assert against fixtures instead of against what ships.
            return new URLClassLoader(new URL[] { MAIN_RESOURCES.toUri().toURL() }, null);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path bundleDirectory() {
        return MAIN_RESOURCES.resolve(basename).getParent();
    }

    private static List<Path> catalogueFiles() throws IOException {
        Pattern naming = Pattern.compile(
            Pattern.quote(MAIN_RESOURCES.resolve(basename).getFileName().toString()) + "(?:_(.+))?\\.properties"
        );
        try (Stream<Path> entries = Files.list(bundleDirectory())) {
            return entries.filter(path -> naming.matcher(path.getFileName().toString()).matches()).sorted().toList();
        }
    }

    private static Map<String, Properties> discoverCatalogues() throws IOException {
        Pattern naming = Pattern.compile(
            Pattern.quote(MAIN_RESOURCES.resolve(basename).getFileName().toString()) + "(?:_(.+))?\\.properties"
        );
        Map<String, Properties> discovered = new LinkedHashMap<>();
        for (Path file : catalogueFiles()) {
            Matcher matcher = naming.matcher(file.getFileName().toString());
            assertThat(matcher.matches()).isTrue();
            String tag = matcher.group(1) == null ? "" : matcher.group(1);
            Properties values = new Properties();
            try {
                values.load(Reader.of(strictUtf8(Files.readAllBytes(file))));
            } catch (CharacterCodingException e) {
                // everyCatalogueIsValidUtf8OnDisk reports this properly; nothing else can run against the file.
                throw new UncheckedIOException(new IOException(file + " is not UTF-8", e));
            }
            discovered.put(tag, values);
        }
        return discovered;
    }

    /** Every key whose value carries a character outside ASCII — the set at risk, derived rather than listed. */
    private static TreeSet<String> accentedKeys(Properties bundle) {
        TreeSet<String> keys = new TreeSet<>();
        for (String key : bundle.stringPropertyNames()) {
            if (bundle.getProperty(key).chars().anyMatch(c -> c > 0x7F)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String anyAccentedValue() {
        return catalogues
            .entrySet()
            .stream()
            .flatMap(catalogue -> accentedKeys(catalogue.getValue()).stream().map(catalogue.getValue()::getProperty))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no catalogue carries an accented value, so nothing here is being tested"));
    }

    private static Locale localeOf(String tag) {
        return tag.isEmpty() ? Locale.ROOT : Locale.forLanguageTag(tag.replace('_', '-'));
    }

    private static String suffixOf(String tag) {
        return tag.isEmpty() ? "" : "_" + tag;
    }
}
