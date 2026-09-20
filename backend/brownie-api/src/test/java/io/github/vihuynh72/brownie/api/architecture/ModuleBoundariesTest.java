package io.github.vihuynh72.brownie.api.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The boundaries the design depends on, checked against the compiled
 * classes so that crossing one fails the build instead of waiting to be
 * noticed in review. Each rule says what may not happen and why it would
 * matter if it did; none of them describes how the code is laid out beyond
 * that.
 *
 * <p>What is read here is this module and the ones it is built from (the
 * core, the storage adapter, the model adapter). The worker is a separate
 * program that this module does not contain; it has a test of its own.
 */
class ModuleBoundariesTest {

    private static JavaClasses production;

    @BeforeAll
    static void readCompiledClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.vihuynh72.brownie");
    }

    /**
     * The rules of the product (what a document is, when an export is
     * allowed, what deletion removes) live in the core and must be testable
     * and readable without a web server, a database driver or a vendor's
     * library in the way. It is written as what the core may use, which is
     * the JDK (its XML reader included), logging and itself, because a list
     * of what it may not use is out of date the day a new library arrives.
     */
    @Test
    void theCoreUsesNothingButTheJdkLoggingAndItself() {
        ArchRule rule = classes().that().resideInAPackage("io.github.vihuynh72.brownie.core..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "javax.xml..", "org.slf4j..", "io.github.vihuynh72.brownie.core..");
        rule.check(production);
    }

    /** Stated apart from the rule above because it is about a different thing: nothing the database can do belongs in the core. */
    @Test
    void theCoreNeverNamesTheDatabase() {
        ArchRule rule = noClasses().that().resideInAPackage("io.github.vihuynh72.brownie.core..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..");
        rule.check(production);
    }

    /**
     * A controller turns a request into a call and a result into a response.
     * One that opened a Word file, ran SQL or talked to the blob store
     * itself would be doing so outside the checks (tenant context, size
     * limits, the scan gate) that the services and adapters apply.
     */
    @Test
    void controllersNeverTouchParsersStorageOrTheDatabaseDirectly() {
        ArchRule rule = noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.apache.poi..", "org.apache.pdfbox..", "com.azure..", "com.openai..", "org.springframework.ai..",
                        "org.springframework.jdbc..", "java.sql..", "javax.sql..",
                        "io.github.vihuynh72.brownie.api.persistence..", "io.github.vihuynh72.brownie.storage..");
        rule.check(production);
    }

    /**
     * Every statement the API runs must first say who it is running for,
     * because row-level security answers from that. The persistence package
     * is where that is done; SQL anywhere else would be SQL that might not.
     * So nothing else may hold anything a statement can be run through: a
     * template, a data source, a connection or a statement. Naming one of
     * the database's exceptions is allowed, which is how a failure to reach
     * it is recognised and answered.
     */
    @Test
    void onlyThePersistencePackageCanRunSql() {
        ArchRule rule = noClasses().that().resideInAPackage("io.github.vihuynh72.brownie.api..")
                .and().resideOutsideOfPackage("io.github.vihuynh72.brownie.api.persistence..")
                .should().dependOnClassesThat(SOMETHING_A_STATEMENT_CAN_BE_RUN_THROUGH);
        rule.check(production);
    }

    static final DescribedPredicate<JavaClass> SOMETHING_A_STATEMENT_CAN_BE_RUN_THROUGH = resideInAnyPackage(
                    "org.springframework.jdbc.core..", "org.springframework.jdbc.datasource..",
                    "org.springframework.jdbc.object..", "javax.sql..")
            .or(resideInAPackage("java.sql..").and(not(assignableTo(Throwable.class))))
            .as("something a statement can be run through");

    /**
     * The libraries that read untrusted files are the likeliest place for a
     * hostile upload to do harm, so everything that uses them is kept in one
     * package, where it can be found, bounded, and one day moved out of this
     * process altogether.
     */
    @Test
    void onlyTheDocumentPackageUsesTheLibrariesThatReadUntrustedFiles() {
        ArchRule rule = noClasses().that().resideInAPackage("io.github.vihuynh72.brownie..")
                .and().resideOutsideOfPackage("io.github.vihuynh72.brownie.api.document..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.poi..", "org.apache.pdfbox..", "org.apache.xmlbeans..");
        rule.check(production);
    }

    /** One adapter each for the model provider and for blob storage, so that a change of vendor is a change in one place. */
    @Test
    void vendorSdksStayInsideTheirAdapters() {
        noClasses().that().resideOutsideOfPackage("io.github.vihuynh72.brownie.ai..")
                .and().resideInAPackage("io.github.vihuynh72.brownie..")
                .should().dependOnClassesThat().resideInAnyPackage("com.openai..", "org.springframework.ai..")
                .check(production);
        noClasses().that().resideOutsideOfPackage("io.github.vihuynh72.brownie.storage..")
                .and().resideInAPackage("io.github.vihuynh72.brownie..")
                .should().dependOnClassesThat().resideInAnyPackage("com.azure..")
                .check(production);
    }

    /** A repository is reached through the port the core defines, so nothing but configuration can come to depend on how it is stored. */
    @Test
    void jdbcRepositoriesAreNotPublic() {
        ArchRule rule = classes().that().resideInAPackage("io.github.vihuynh72.brownie.api.persistence.jdbc..")
                .and().haveSimpleNameStartingWith("Jdbc")
                .should().notBePublic();
        rule.check(production);
    }
}
