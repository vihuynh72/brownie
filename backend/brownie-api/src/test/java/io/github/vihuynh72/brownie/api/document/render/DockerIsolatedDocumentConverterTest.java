package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.core.prepare.ConversionFormatDisabledException;
import io.github.vihuynh72.brownie.core.prepare.ConvertedDocument;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import io.github.vihuynh72.brownie.core.prepare.DocumentConversionException;
import io.github.vihuynh72.brownie.core.prepare.DocumentConverter;
import io.github.vihuynh72.brownie.core.prepare.EnabledFormatsDocumentConverter;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Converts real files in the real renderer image -- not a mock -- so this
 * proves the network-disabled, resource-capped container reads each format
 * with the filter it is told to use, that what comes back is a Word document
 * POI opens, and that the image's hardening holds.
 *
 * <p>The image is the one the application would run: {@code
 * BROWNIE_RENDER_IMAGE} (or the {@code brownie.render.image} system
 * property) when set, else {@code brownie-spike-renderer:pinned}. The
 * hardening test needs an image built from the current
 * {@code spike/docx-binding/render/Dockerfile}; an older build of the tag
 * fails it, loudly, which is the point.
 */
@DockerTest
class DockerIsolatedDocumentConverterTest {

    private static final String IMAGE = imageUnderTest();
    private static final String[] SAMPLE_FORMS = {"membership-application", "equipment-request", "reference-letter"};
    private static final Map<String, String> TEXT_IN_EACH_FORM = Map.of(
            "membership-application", "Full name: ____",
            "equipment-request", "Equipment Request Form",
            "reference-letter", "Dear ________,");

    private final DockerIsolatedDocumentConverter converter = new DockerIsolatedDocumentConverter(
            IMAGE, null, null, DockerIsolatedDocumentConverter.DEFAULT_OUTPUT_MAX_BYTES);

    @Test
    void everySampleFormInEveryFormatBecomesAWordDocumentThatOpens() throws IOException {
        Map<String, ConvertibleFormat> formats = Map.of(
                "doc", ConvertibleFormat.WORD_97,
                "rtf", ConvertibleFormat.RTF,
                "odt", ConvertibleFormat.ODT,
                "ott", ConvertibleFormat.ODT_TEMPLATE);
        for (String form : SAMPLE_FORMS) {
            for (Map.Entry<String, ConvertibleFormat> format : formats.entrySet()) {
                byte[] source = Files.readAllBytes(sampleForm(form + "." + format.getKey()));

                ConvertedDocument converted = converter.convertToDocx(source, format.getValue());

                assertThat(textOf(converted.docxBytes())).as(form + "." + format.getKey())
                        .contains(TEXT_IN_EACH_FORM.get(form));
                assertThat(converted.converterVersion())
                        .contains("LibreOffice")
                        .containsPattern("image sha256:[0-9a-f]{64}")
                        .endsWith("import filter " + DockerIsolatedDocumentConverter.importFilterFor(format.getValue()));
            }
        }
    }

    /**
     * The renderer, told nothing about its input, turns plain text named
     * .docx into a PDF anyway. Told which filter to use, LibreOffice opens
     * nothing that is not that format.
     */
    @Test
    void plainTextCalledAWordFileDoesNotOpenAsSomethingElse() {
        byte[] text = "this is plain text, not a Word 97 document".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> converter.convertToDocx(text, ConvertibleFormat.WORD_97))
                .isInstanceOfSatisfying(DocumentConversionException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(DocumentConversionException.Reason.CANNOT_OPEN));
    }

    /**
     * The document carries a Basic macro bound to its opening, which would
     * write a file next to the output and stamp the text. The conversion
     * succeeding at all shows the output directory held the converted file
     * and nothing else (the converter refuses anything more); the text shows
     * the document was not changed on the way.
     */
    @Test
    void anOdtWhoseOpeningMacroWouldWriteAFileComesBackAsTheDocumentAlone() throws IOException {
        ConvertedDocument converted = converter.convertToDocx(odtWithOpeningMacro(), ConvertibleFormat.ODT);

        assertThat(textOf(converted.docxBytes()))
                .contains("Macro test form. Full name: ________")
                .doesNotContain("MACRO RAN");
    }

    @Test
    void anRtfWithAnEmbeddedFileConvertsWithoutCarryingTheFileAlong() throws IOException {
        ConvertedDocument converted = converter.convertToDocx(rtfWithEmbeddedPackage(), ConvertibleFormat.RTF);

        assertThat(textOf(converted.docxBytes())).contains("Before the object.").contains("After the object.");
        assertThat(allPartsOf(converted.docxBytes())).doesNotContain(EMBEDDED_PAYLOAD);
    }

    /**
     * A 20,000-row table keeps LibreOffice busy for many minutes, so it is
     * certainly still running when a five-second deadline passes. What
     * matters is the container: stopping only the local {@code docker run}
     * client would leave it running on the daemon.
     */
    @Test
    void aConversionStillRunningAtItsDeadlineIsStoppedAndLeavesNoContainerBehind() throws Exception {
        DockerIsolatedDocumentConverter impatient = new DockerIsolatedDocumentConverter(
                IMAGE, null, null, DockerIsolatedDocumentConverter.DEFAULT_OUTPUT_MAX_BYTES, Duration.ofSeconds(5));

        assertThatThrownBy(() -> impatient.convertToDocx(rtfWithHugeTable(20_000), ConvertibleFormat.RTF))
                .isInstanceOfSatisfying(DocumentConversionException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(DocumentConversionException.Reason.TIMED_OUT));
        assertThat(waitUntilNoConverterContainerRuns()).as("a converter container was still running after the deadline").isTrue();
    }

    @Test
    void aFormatSwitchedOffIsRefusedWhileTheOthersStillConvert() throws IOException {
        DocumentConverter onlyOdt = new EnabledFormatsDocumentConverter(converter, EnumSet.of(ConvertibleFormat.ODT));
        byte[] rtf = Files.readAllBytes(sampleForm("membership-application.rtf"));

        assertThatThrownBy(() -> onlyOdt.convertToDocx(rtf, ConvertibleFormat.RTF))
                .isInstanceOfSatisfying(ConversionFormatDisabledException.class,
                        refused -> assertThat(refused.format()).isEqualTo(ConvertibleFormat.RTF));
        ConvertedDocument converted =
                onlyOdt.convertToDocx(Files.readAllBytes(sampleForm("membership-application.odt")), ConvertibleFormat.ODT);
        assertThat(textOf(converted.docxBytes())).contains("Full name: ____");
    }

    /** Word 95 and Pages have no sample the image could have made, so their filters are checked by name in its registry. */
    @Test
    void everyImportFilterTheConverterNamesIsRegisteredInTheImage() throws Exception {
        String registry = runInImage("cat", "/usr/lib/libreoffice/share/registry/writer.xcd");

        for (ConvertibleFormat format : ConvertibleFormat.values()) {
            String filter = DockerIsolatedDocumentConverter.importFilterFor(format);
            assertThat(isRegisteredImportFilter(registry, filter)).as(format + " reads with " + filter).isTrue();
        }
    }

    @Test
    void aPagesFileThatIsNotReallyPagesDoesNotOpen() throws IOException {
        ByteArrayOutputStream pages = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(pages)) {
            zip.putNextEntry(new ZipEntry("Form.pages/Index/Document.iwa"));
            zip.write("not an iwork archive".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> converter.convertToDocx(pages.toByteArray(), ConvertibleFormat.PAGES))
                .isInstanceOfSatisfying(DocumentConversionException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(DocumentConversionException.Reason.CANNOT_OPEN));
    }

    /**
     * A user profile that tries to turn macros back on, load, run and keep
     * VBA, and always update links is put where LibreOffice starts from.
     * LibreOffice rewrites the profile as it finishes: every one of those
     * settings is gone from it, because the image finalized them beneath the
     * profile, while an ordinary setting in the same profile survives, which
     * shows the profile was read and written back at all. (Conversion never
     * runs a document's macros whatever the settings say; these settings are
     * the second wall, and this is how to see that it stands.)
     */
    @Test
    void theImageKeepsItsHardenedSettingsWhateverAProfileSays() throws Exception {
        Path job = Files.createTempDirectory("brownie-converter-hardening-");
        try {
            Path in = Files.createDirectory(job.resolve("in"));
            Path out = Files.createDirectory(job.resolve("out"));
            Path profile = Files.createDirectory(job.resolve("profile"));
            Path user = Files.createDirectory(profile.resolve("user"));
            Path settings = Files.writeString(user.resolve("registrymodifications.xcu"), PROFILE_LOWERING_THE_HARDENED_SETTINGS);
            Files.write(in.resolve("input.odt"), Files.readAllBytes(sampleForm("membership-application.odt")));
            openToTheSandboxUser(job, in, out, profile, user, settings);

            String log = runInImage(
                    List.of("-v", in + ":/in:ro", "-v", out + ":/out", "-v", profile + ":/profile"),
                    "soffice", "--headless", "--norestore", "--nolockcheck", "--nodefault",
                    "-env:UserInstallation=file:///profile", "--infilter=writer8",
                    "--convert-to", "docx:MS Word 2007 XML", "--outdir", "/out", "/in/input.odt");

            assertThat(out.resolve("input.docx")).as(log).isRegularFile();
            // LibreOffice writes the profile back as the sandbox user, readable by that user
            // alone, so on Linux this process may not open it; the image reads it instead.
            String rewritten = runInImage(List.of("-v", profile + ":/profile:ro"),
                    "cat", "/profile/user/registrymodifications.xcu");
            assertThat(rewritten).contains(setting("/org.openoffice.Office.Common/Undo", "Steps") + " oor:op=\"fuse\"><value>42</value>");
            for (String[] hardened : HARDENED_SETTINGS) {
                assertThat(rewritten).as(hardened[0] + "/" + hardened[1]).doesNotContain(setting(hardened[0], hardened[1]));
            }
        } finally {
            deleteQuietly(job);
        }
    }

    private static final String[][] HARDENED_SETTINGS = {
            {"/org.openoffice.Office.Common/Security/Scripting", "MacroSecurityLevel"},
            {"/org.openoffice.Office.Common/Security/Scripting", "DisableMacrosExecution"},
            {"/org.openoffice.Office.Common/Security/Scripting", "BlockUntrustedRefererLinks"},
            {"/org.openoffice.Office.Writer/Filter/Import/VBA", "Load"},
            {"/org.openoffice.Office.Writer/Filter/Import/VBA", "Executable"},
            {"/org.openoffice.Office.Writer/Filter/Import/VBA", "Save"},
            {"/org.openoffice.Office.Writer/Content/Update", "Link"}};

    private static final String PROFILE_LOWERING_THE_HARDENED_SETTINGS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <oor:items xmlns:oor="http://openoffice.org/2001/registry" xmlns:xs="http://www.w3.org/2001/XMLSchema" \
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>0</value></prop></item>
            <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>false</value></prop></item>
            <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="BlockUntrustedRefererLinks" oor:op="fuse"><value>false</value></prop></item>
            <item oor:path="/org.openoffice.Office.Writer/Filter/Import/VBA"><prop oor:name="Load" oor:op="fuse"><value>true</value></prop></item>
            <item oor:path="/org.openoffice.Office.Writer/Filter/Import/VBA"><prop oor:name="Executable" oor:op="fuse"><value>true</value></prop></item>
            <item oor:path="/org.openoffice.Office.Writer/Filter/Import/VBA"><prop oor:name="Save" oor:op="fuse"><value>true</value></prop></item>
            <item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>2</value></prop></item>
            <item oor:path="/org.openoffice.Office.Common/Undo"><prop oor:name="Steps" oor:op="fuse"><value>42</value></prop></item>
            </oor:items>
            """;

    private static String setting(String path, String name) {
        return "<item oor:path=\"" + path + "\"><prop oor:name=\"" + name + "\"";
    }

    private static String textOf(byte[] docx) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx));
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /** An OpenDocument text whose Basic macro is bound to the document opening, as a hostile form would carry it. */
    private static byte[] odtWithOpeningMacro() throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" \
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" \
                xmlns:script="urn:oasis:names:tc:opendocument:xmlns:script:1.0" \
                xmlns:xlink="http://www.w3.org/1999/xlink" office:version="1.3">
                 <office:scripts>
                  <office:event-listeners>
                   <script:event-listener script:language="ooo:script" script:event-name="dom:load" \
                xlink:href="vnd.sun.star.script:Standard.Module1.Main?language=Basic&amp;location=document" xlink:type="simple"/>
                  </office:event-listeners>
                 </office:scripts>
                 <office:body><office:text><text:p>Macro test form. Full name: ________</text:p></office:text></office:body>
                </office:document-content>
                """;
        String manifest = """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">
                 <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
                 <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                 <manifest:file-entry manifest:full-path="Basic/script-lc.xml" manifest:media-type="text/xml"/>
                 <manifest:file-entry manifest:full-path="Basic/Standard/script-lb.xml" manifest:media-type="text/xml"/>
                 <manifest:file-entry manifest:full-path="Basic/Standard/Module1.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """;
        String libraries = """
                <?xml version="1.0" encoding="UTF-8"?>
                <library:libraries xmlns:library="http://openoffice.org/2000/library" xmlns:xlink="http://www.w3.org/1999/xlink">
                 <library:library library:name="Standard" library:link="false"/>
                </library:libraries>
                """;
        String library = """
                <?xml version="1.0" encoding="UTF-8"?>
                <library:library xmlns:library="http://openoffice.org/2000/library" library:name="Standard" \
                library:readonly="false" library:passwordprotected="false">
                 <library:element library:name="Module1"/>
                </library:library>
                """;
        String module = """
                <?xml version="1.0" encoding="UTF-8"?>
                <script:module xmlns:script="http://openoffice.org/2000/script" script:name="Module1" \
                script:language="StarBasic">Sub Main
                  Dim n As Integer
                  n = FreeFile
                  Open &quot;/out/macro-ran.txt&quot; For Output As #n
                  Print #n, &quot;macro ran&quot;
                  Close #n
                  ThisComponent.getText().getStart().setString(&quot;MACRO RAN &quot;)
                End Sub
                </script:module>
                """;
        ByteArrayOutputStream odt = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(odt)) {
            byte[] mimetype = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII);
            ZipEntry first = new ZipEntry("mimetype");
            first.setMethod(ZipEntry.STORED);
            first.setSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            first.setCrc(crc.getValue());
            zip.putNextEntry(first);
            zip.write(mimetype);
            zip.closeEntry();
            Map<String, String> parts = new LinkedHashMap<>();
            parts.put("META-INF/manifest.xml", manifest);
            parts.put("content.xml", content);
            parts.put("Basic/script-lc.xml", libraries);
            parts.put("Basic/Standard/script-lb.xml", library);
            parts.put("Basic/Standard/Module1.xml", module);
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return odt.toByteArray();
    }

    private static final String EMBEDDED_PAYLOAD = "hello from inside the package";

    /**
     * An RTF carrying a file inside an OLE "Package" object, the way Word
     * writes one (with a result shown in its place), between two paragraphs.
     */
    private static byte[] rtfWithEmbeddedPackage() {
        ByteArrayOutputStream nativeData = new ByteArrayOutputStream();
        nativeData.writeBytes(new byte[] {0x02, 0x00});
        nativeData.writeBytes(ascii0("hello.txt"));
        nativeData.writeBytes(ascii0("C:\\hello.txt"));
        nativeData.writeBytes(new byte[] {0x00, 0x00, 0x03, 0x00});
        byte[] temporaryPath = ascii0("C:\\Temp\\hello.txt");
        nativeData.writeBytes(littleEndian(temporaryPath.length));
        nativeData.writeBytes(temporaryPath);
        byte[] payload = EMBEDDED_PAYLOAD.getBytes(StandardCharsets.US_ASCII);
        nativeData.writeBytes(littleEndian(payload.length));
        nativeData.writeBytes(payload);

        ByteArrayOutputStream object = new ByteArrayOutputStream();
        object.writeBytes(littleEndian(0x0501));
        object.writeBytes(littleEndian(2));
        byte[] className = ascii0("Package");
        object.writeBytes(littleEndian(className.length));
        object.writeBytes(className);
        object.writeBytes(littleEndian(0));
        object.writeBytes(littleEndian(0));
        object.writeBytes(littleEndian(nativeData.size()));
        object.writeBytes(nativeData.toByteArray());

        String rtf = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Times New Roman;}}\n"
                + "{\\pard Before the object.\\par}\n"
                + "{\\pard {\\object\\objemb\\objw1440\\objh720{\\*\\objclass Package}{\\*\\objdata "
                + HexFormat.of().formatHex(object.toByteArray())
                + "}{\\result {\\pard Embedded file\\par}}}\\par}\n"
                + "{\\pard After the object.\\par}\n}";
        return rtf.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] rtfWithHugeTable(int rows) {
        String row = "\\trowd\\cellx2000\\cellx4000\\cellx6000\\cellx8000 A\\cell B\\cell C\\cell D\\cell\\row\n";
        return ("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Times New Roman;}}\n" + row.repeat(rows) + "\\pard End.\\par}")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] ascii0(String text) {
        return (text + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(int value) {
        return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    /** Every part of the package, one byte to one character, so any embedded bytes can be searched for. */
    private static String allPartsOf(byte[] docx) throws IOException {
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            while (zip.getNextEntry() != null) {
                all.append(new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
        return all.toString();
    }

    private static boolean isRegisteredImportFilter(String registry, String filter) {
        int at = 0;
        while ((at = registry.indexOf("<node oor:name=\"" + filter + "\" oor:op=\"replace\">", at)) >= 0) {
            int end = registry.indexOf("</node>", at);
            String node = registry.substring(at, end < 0 ? registry.length() : end);
            int flags = node.indexOf("<prop oor:name=\"Flags\">");
            if (flags >= 0 && node.indexOf("IMPORT", flags) >= 0 && node.contains("FilterService")) {
                return true;
            }
            at = end < 0 ? registry.length() : end;
        }
        return false;
    }

    private static boolean waitUntilNoConverterContainerRuns() throws Exception {
        for (int i = 0; i < 150; i++) {
            Process ps = new ProcessBuilder("docker", "ps", "--filter", "name=brownie-convert-", "--format", "{{.Names}}")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ps.waitFor(10, TimeUnit.SECONDS);
            if (output.isBlank()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static String runInImage(String... command) throws Exception {
        return runInImage(List.of(), command);
    }

    /** Runs one command in the image under test with the sandbox's own limits, and returns what it printed. */
    private static String runInImage(List<String> mounts, String... command) throws Exception {
        String name = "brownie-converter-test-" + UUID.randomUUID();
        List<String> run = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--name", name, "--network", "none", "--read-only",
                "--tmpfs", "/tmp:rw,size=64m,mode=1777",
                "--tmpfs", "/home/renderer:rw,size=32m,mode=0700,uid=10001,gid=10001",
                "--memory=512m", "--memory-swap=512m", "--pids-limit=128", "--cpus=1", "--cap-drop=ALL",
                "--security-opt", "no-new-privileges"));
        run.addAll(mounts);
        run.add(IMAGE);
        run.addAll(List.of(command));
        Path printed = Files.createTempFile("brownie-converter-test-", ".log");
        try {
            Process process = new ProcessBuilder(run).redirectErrorStream(true).redirectOutput(printed.toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                new ProcessBuilder("docker", "kill", name).start().waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("the command in the image did not finish in time: " + command[0]);
            }
            return Files.readString(printed, StandardCharsets.ISO_8859_1);
        } finally {
            Files.deleteIfExists(printed);
        }
    }

    /** The container runs as its own user, which on Linux has to be let into directories this process made. */
    private static void openToTheSandboxUser(Path... paths) {
        for (Path path : paths) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                        Files.isDirectory(path) ? "rwxrwxrwx" : "rw-rw-rw-"));
            } catch (UnsupportedOperationException | IOException e) {
                // A filesystem without these bits does not enforce them either.
            }
        }
    }

    /** Best effort: the container may leave files owned by its own user that this process cannot remove. */
    private static void deleteQuietly(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Left for the operating system's own temporary-file cleanup.
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // As above.
        }
    }

    private static String imageUnderTest() {
        String configured = System.getProperty("brownie.render.image", System.getenv("BROWNIE_RENDER_IMAGE"));
        return configured == null || configured.isBlank() ? "brownie-spike-renderer:pinned" : configured.trim();
    }

    private static Path sampleForm(String fileName) {
        Path path = repositoryRoot().resolve("fixtures/public/forms").resolve(fileName);
        assertThat(path).as("missing fixture").isRegularFile();
        return path;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures/public/forms"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from the test working directory");
    }
}
