package io.github.vihuynh72.brownie.api.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import io.github.vihuynh72.brownie.core.action.ActionRepository;
import io.github.vihuynh72.brownie.core.action.ActionService;
import io.github.vihuynh72.brownie.core.action.CalendarEventWriter;
import io.github.vihuynh72.brownie.core.action.DriveFileWriter;
import io.github.vihuynh72.brownie.core.action.GoogleDocs;
import io.github.vihuynh72.brownie.core.action.PreparedWrite;
import io.github.vihuynh72.brownie.core.connector.DriveFileReader;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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

    private static final String GOOGLE_ADAPTER = "io.github.vihuynh72.brownie.api.connector.google..";

    /**
     * Reading a person's Drive files is for the classes that talk to Google,
     * and for the stand-in that refuses every read where nothing may; nothing
     * else can be plugged in to read them.
     */
    @Test
    void onlyTheGoogleAdapterOrItsStandInReadsDrive() {
        classes().that().implement(DriveFileReader.class)
                .should().resideInAPackage(GOOGLE_ADAPTER)
                .orShould().haveFullyQualifiedName("io.github.vihuynh72.brownie.api.connector.NotConfiguredDrive")
                .check(production);
    }

    /** The classes that may send a change to Google, each only the change its own kind of action makes. */
    private static final java.util.Set<String> GOOGLE_WRITERS = java.util.Set.of(
            "io.github.vihuynh72.brownie.api.connector.google.GoogleDriveWriter",
            "io.github.vihuynh72.brownie.api.connector.google.GoogleCalendarWriter",
            "io.github.vihuynh72.brownie.api.connector.google.GoogleDocsClient");
    private static final DescribedPredicate<JavaClass> A_GOOGLE_WRITER =
            DescribedPredicate.describe("a Google writer", javaClass -> GOOGLE_WRITERS.contains(javaClass.getName()));

    /**
     * A change reaches Google only one way: through the one method of
     * GoogleHttp that sends the writes it lists, called only by the writer
     * classes. The class that owns the token and revocation endpoints may
     * post to them; nothing else that talks to Google posts, and nothing
     * puts, patches, deletes or sends a request of a kind chosen at run time.
     */
    @Test
    void theGoogleAdapterWritesOnlyThroughItsListOfWrites() {
        noClasses().that().resideInAPackage(GOOGLE_ADAPTER)
                .and().doNotHaveSimpleName("GoogleOAuthClient").and().doNotHaveSimpleName("GoogleHttp")
                .should().callMethod(RestClient.class, "post")
                .check(production);
        noClasses().that().resideInAPackage(GOOGLE_ADAPTER)
                .should().callMethod(RestClient.class, "put")
                .orShould().callMethod(RestClient.class, "patch")
                .orShould().callMethod(RestClient.class, "delete")
                .orShould().callMethod(RestClient.class, "method", HttpMethod.class)
                .check(production);
        noClasses().that(not(A_GOOGLE_WRITER))
                .should().accessTargetWhere(DescribedPredicate.describe("GoogleHttp's write",
                        access -> access.getTarget().getOwner().getSimpleName().equals("GoogleHttp") && access.getName().equals("write")))
                .check(production);
    }

    /** Changing something in a person's account is for the classes that talk to Google, or the stand-ins that refuse where nothing may. */
    @Test
    void onlyTheGoogleAdapterOrItsStandInsImplementAWriter() {
        classes().that().implement(DriveFileWriter.class).or().implement(GoogleDocs.class).or().implement(CalendarEventWriter.class)
                .should().resideInAPackage(GOOGLE_ADAPTER)
                .orShould().haveNameMatching("io\\.github\\.vihuynh72\\.brownie\\.api\\.connector\\.NotConfigured.*")
                .check(production);
    }

    /**
     * Only the action service, which checks every approval, reaches a writer;
     * nothing else can make one change anything. Accesses cover calls and
     * method references alike, so a reference handed on is caught too.
     */
    @Test
    void onlyTheActionPackageUsesAWriter() {
        noClasses().that().resideOutsideOfPackage("io.github.vihuynh72.brownie.core.action..")
                .should().accessTargetWhere(DescribedPredicate.describe("a method a writer port declares",
                        ModuleBoundariesTest::accessesAWriterPortMethod))
                .check(production);
    }

    /**
     * Within the action package too, a change is sent only by what a handler
     * prepared for an approved action: the port methods that change something
     * (creating a file, adding an event, adding to a Doc) are called only from
     * a prepared write, and a prepared write is sent only by the action
     * service, after its claim. A proposal, a readback or a reconciliation
     * can only read.
     */
    @Test
    void onlyAPreparedWriteSendsAChange() {
        noClasses().should().accessTargetWhere(DescribedPredicate.describe(
                        "a method that changes something at the provider, from anywhere but a prepared write's send",
                        access -> changesSomething(access) && !(access.getOrigin().getName().equals("send")
                                && access.getOriginOwner().isAssignableTo(PreparedWrite.class))))
                .check(production);
        noClasses().that().doNotHaveFullyQualifiedName(ActionService.class.getName())
                .should().accessTargetWhere(DescribedPredicate.describe("sending or reading back a prepared write",
                        access -> (access.getName().equals("send") || access.getName().equals("readBack"))
                                && access.getTarget().getOwner().isAssignableTo(PreparedWrite.class)))
                .check(production);
    }

    private static boolean changesSomething(com.tngtech.archunit.core.domain.JavaAccess<?> access) {
        return (access.getName().equals("createFile") && access.getTarget().getOwner().isAssignableTo(DriveFileWriter.class))
                || (access.getName().equals("append") && access.getTarget().getOwner().isAssignableTo(GoogleDocs.class))
                || (access.getName().equals("insertEvent") && access.getTarget().getOwner().isAssignableTo(CalendarEventWriter.class));
    }

    /** An access to one of the ports' own methods, whether through the port or through a class implementing it. */
    private static boolean accessesAWriterPortMethod(com.tngtech.archunit.core.domain.JavaAccess<?> access) {
        for (Class<?> port : java.util.List.of(DriveFileWriter.class, GoogleDocs.class, CalendarEventWriter.class)) {
            boolean declared = java.util.Arrays.stream(port.getDeclaredMethods()).anyMatch(method -> method.getName().equals(access.getName()));
            if (declared && access.getTarget().getOwner().isAssignableTo(port)) {
                return true;
            }
        }
        return false;
    }

    /** A writer remembers nothing between calls: no token, no file, nothing a second request could find. */
    @Test
    void aWriterKeepsNothingBetweenCalls() {
        classes().that().implement(DriveFileWriter.class).or().implement(GoogleDocs.class).or().implement(CalendarEventWriter.class)
                .should().haveOnlyFinalFields().check(production);
    }

    /** A Drive reader remembers nothing between calls: no token, no file, nothing a second request could find. */
    @Test
    void aDriveReaderKeepsNothingBetweenCalls() {
        classes().that().implement(DriveFileReader.class).should().haveOnlyFinalFields().check(production);
    }

    /** Nothing that talks to Google writes to the disk, so a token or a person's file is never left in a file. */
    @Test
    void theGoogleAdapterNeverTouchesFiles() {
        noClasses().that().resideInAPackage(GOOGLE_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("java.nio.file..")
                .orShould().dependOnClassesThat().belongToAnyOf(
                        File.class, FileInputStream.class, FileOutputStream.class, FileReader.class, FileWriter.class, RandomAccessFile.class)
                .check(production);
    }

    /**
     * The adapter answers questions about Google and nothing else: it cannot
     * reach sources, stored files or the database, so what it reads reaches
     * them only through the checks the services apply.
     */
    @Test
    void theGoogleAdapterReachesNothingButGoogle() {
        noClasses().that().resideInAPackage(GOOGLE_ADAPTER)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.github.vihuynh72.brownie.core.source..", "io.github.vihuynh72.brownie.core.artifact..",
                        "io.github.vihuynh72.brownie.api.persistence..")
                .check(production);
    }

    /** Three reads and nothing else: a fourth method would be a way to ask Drive for something more. */
    @Test
    void theDriveReaderHasExactlyThreeReads() {
        assertThat(java.util.Arrays.stream(DriveFileReader.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).sorted())
                .containsExactly("describeFile", "readGoogleDocAsText", "readTextFile");
    }

    /**
     * Nothing that handles what a model wrote can reach a change in a person's
     * account, however indirectly: a model's output (or text in a source it
     * read) that says "approved" must have no path to an approval. Approving
     * is a person's request, with their session and the payload's hash.
     */
    @Test
    void nothingThatHandlesModelOutputCanReachAnActionEvenIndirectly() {
        noClasses().that().resideInAnyPackage(
                        "io.github.vihuynh72.brownie.ai..", "io.github.vihuynh72.brownie.core.assist..",
                        "io.github.vihuynh72.brownie.core.generation..", "io.github.vihuynh72.brownie.api.generation..")
                .should().transitivelyDependOnClassesThat().resideInAPackage("io.github.vihuynh72.brownie.core.action..")
                .check(production);
    }

    /**
     * An action moves along only through the service that checks every
     * approval: nothing else may claim, send, finish or cancel one, so there
     * is no second way to a change in someone's account.
     */
    @Test
    void onlyTheActionServiceMovesAnActionAlong() {
        noClasses().that().doNotHaveFullyQualifiedName(ActionService.class.getName())
                .and().resideOutsideOfPackage("io.github.vihuynh72.brownie.api.persistence..")
                .should().accessTargetWhere(DescribedPredicate.describe("a method of the action repository",
                        access -> !access.getName().equals("<init>") && access.getTarget().getOwner().isAssignableTo(ActionRepository.class)))
                .check(production);
    }

    /**
     * Only the classes that talk to Google hold an HTTP client, so there is
     * no other place from which a request could reach a person's account.
     */
    @Test
    void nothingButTheGoogleAdapterHoldsAnHttpClient() {
        noClasses().that().resideInAPackage("io.github.vihuynh72.brownie..")
                .and().resideOutsideOfPackage(GOOGLE_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("java.net.http..")
                .orShould().dependOnClassesThat().belongToAnyOf(RestClient.class, RestTemplate.class, HttpURLConnection.class)
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
