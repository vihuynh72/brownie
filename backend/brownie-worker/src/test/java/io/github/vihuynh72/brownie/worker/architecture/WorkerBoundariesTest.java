package io.github.vihuynh72.brownie.worker.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The same boundaries the API keeps, checked for the worker, which is a
 * separate program and is not among the classes the API's own test reads.
 */
class WorkerBoundariesTest {

    private static JavaClasses production;

    @BeforeAll
    static void readCompiledClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.vihuynh72.brownie");
    }

    /**
     * The worker's database role can touch no table; all it may do is call
     * the few routines written for it. Keeping everything a statement can
     * be run through in one package is what makes that list of calls
     * something a person can read from end to end.
     */
    @Test
    void onlyThePersistencePackageCanRunSql() {
        DescribedPredicate<JavaClass> somethingAStatementCanBeRunThrough = resideInAnyPackage(
                        "org.springframework.jdbc.core..", "org.springframework.jdbc.datasource..",
                        "org.springframework.jdbc.object..", "javax.sql..")
                .or(resideInAPackage("java.sql..").and(not(assignableTo(Throwable.class))))
                .as("something a statement can be run through");

        noClasses().that().resideInAPackage("io.github.vihuynh72.brownie.worker..")
                .and().resideOutsideOfPackage("io.github.vihuynh72.brownie.worker.persistence..")
                .should().dependOnClassesThat(somethingAStatementCanBeRunThrough)
                .check(production);
    }

    /** The worker reaches the model provider and blob storage through the same two adapters the API does, and reads no uploaded file itself. */
    @Test
    void theWorkerUsesNoVendorSdkOrFileParsingLibraryDirectly() {
        noClasses().that().resideInAPackage("io.github.vihuynh72.brownie.worker..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.azure..", "com.openai..", "org.springframework.ai..",
                        "org.apache.poi..", "org.apache.pdfbox..", "org.apache.xmlbeans..")
                .check(production);
    }
}
