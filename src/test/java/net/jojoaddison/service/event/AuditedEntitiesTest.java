package net.jojoaddison.service.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.domain.RevokedToken;
import net.jojoaddison.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * That every domain class has been reasoned about, and that the reasoning is not derived from {@code @Document}.
 *
 * <p>Backlog item 45. These two tests guard the two halves of the exclusion decision — <em>coverage</em>, which is
 * mechanical, and <em>mechanism</em>, which is the trap.</p>
 */
class AuditedEntitiesTest {

    /**
     * The one that fails when somebody adds a domain class.
     *
     * <p>⛔ <strong>This must never be satisfied by enumerating today's three classes</strong> — that is the list that
     * goes stale, and item 45 asks for the opposite. It walks {@code net.jojoaddison.domain} on the classpath, so a new
     * class fails the build the moment it compiles, and the person adding it has to decide whether a change to it is an
     * audit-worthy event or infrastructure noise.</p>
     *
     * <p>⛔ <strong>And do not make it pass by adding the new class to {@code SUPPRESSED}.</strong> Suppressing loses an
     * audit row silently; publishing is merely noisy. {@link AuditedEntities}' javadoc says the same thing where somebody
     * reaching for the quick fix will actually be reading.</p>
     */
    @Test
    void everyDomainClassIsEitherPublishedOrDeliberatelySuppressed() {
        List<Class<?>> unclassified = domainClasses().stream().filter(type -> !AuditedEntities.isClassified(type)).toList();

        assertThat(unclassified)
            .as(
                "a new domain class must be classified in AuditedEntities — publish it, or suppress it with a reason. " +
                "Do not reach for suppression to get green: that is the choice that loses an audit row silently."
            )
            .isEmpty();
    }

    /**
     * That {@code @Document} is not the mechanism — pinned as a property of the annotation's discriminating power.
     *
     * <p>⛔ <strong>All three domain classes carry the annotation, so scanning for it selects the whole domain.</strong>
     * An implementation built that way would publish role definitions on every boot and a frame per logout — the flood
     * the suppressions exist to prevent. This asserts both halves: that the annotation cannot separate these classes, and
     * that {@link AuditedEntities} separates them anyway.</p>
     *
     * <p>⚠ Item 45's fact 2 claimed {@code User} carries no {@code @Document}, from
     * {@code grep -n '@Document' .../domain/*.java}. The grep was accurate and the conclusion was wrong: {@code User}
     * declares it <em>fully qualified</em>, which that literal cannot match. The assertion below is what a grep could not
     * be — and it is why this test asserts {@code isAnnotationPresent} rather than trusting either reading.</p>
     */
    @Test
    void theDocumentAnnotationCannotSeparatePublishedFromSuppressed() {
        assertThat(User.class.isAnnotationPresent(Document.class))
            .as("User declares @Document fully qualified — a grep for the short form misses it, reflection does not")
            .isTrue();
        assertThat(Authority.class.isAnnotationPresent(Document.class)).isTrue();
        assertThat(RevokedToken.class.isAnnotationPresent(Document.class)).isTrue();

        // So the annotation has zero discriminating power here, and the classification must not be derived from it.
        assertThat(domainClasses())
            .as("every domain class is a @Document, so scanning for it would publish all of them")
            .allMatch(type -> type.isAnnotationPresent(Document.class));

        assertThat(AuditedEntities.isPublished(User.class)).as("the account is the one entity this stream exists for").isTrue();
        assertThat(AuditedEntities.isPublished(Authority.class)).as("role definitions are not an audit trail").isFalse();
        assertThat(AuditedEntities.isPublished(RevokedToken.class)).as("a logout is not a domain change").isFalse();
    }

    /** An unclassified type answers false at runtime — silent rather than publishing something unreasoned. */
    @Test
    void anUnclassifiedTypeIsNotPublished() {
        assertThat(AuditedEntities.isPublished(String.class)).isFalse();
    }

    /**
     * Every concrete, non-abstract class in the domain package.
     *
     * <p>Abstract classes and enums are excluded because no document is ever an instance of one — {@code
     * AbstractAuditingEntity} is a superclass, never a saved type. Tests are excluded from the import so a fixture in
     * the same package cannot make this pass or fail.</p>
     */
    private static List<Class<?>> domainClasses() {
        JavaClasses imported = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("net.jojoaddison.domain");

        return imported
            .stream()
            .filter(javaClass -> !javaClass.isInterface())
            .filter(javaClass -> !javaClass.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
            .filter(javaClass -> !javaClass.isEnum())
            .filter(javaClass -> !javaClass.getSimpleName().isEmpty())
            .filter(javaClass -> !javaClass.getName().endsWith("package-info"))
            // A nested or synthetic class is not a document type.
            .filter(javaClass -> !javaClass.getName().contains("$"))
            .<Class<?>>map(JavaClass::reflect)
            .toList();
    }
}
