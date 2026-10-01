package io.github.vihuynh72.brownie.core.prepare;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PagesPackagesTest {

    @Test
    void aPackageAlreadyInTheSingleFileLayoutComesBackAsItWas() throws IOException {
        byte[] single = zip(parts("Index/Document.iwa", "document", "Metadata/Properties.plist", "properties",
                "preview.jpg", "picture"));

        assertSame(single, PagesPackages.repack(single));
    }

    @Test
    void anOlderPagesFileIsLeftAlone() throws IOException {
        byte[] older = zip(parts("index.xml.gz", "document", "QuickLook/Thumbnail.jpg", "picture"));

        assertSame(older, PagesPackages.repack(older));
    }

    @Test
    void aFolderCompressedInTheFinderLosesItsOuterFolderAndTheFinderBookkeeping() throws IOException {
        Map<String, String> finder = new LinkedHashMap<>();
        finder.put("Form.pages/", "");
        finder.put("Form.pages/Index/Document.iwa", "document");
        finder.put("Form.pages/preview.jpg", "picture");
        finder.put("__MACOSX/Form.pages/._preview.jpg", "finder");

        Map<String, String> repacked = unzip(PagesPackages.repack(zip(finder)));

        assertEquals(parts("Index/Document.iwa", "document", "preview.jpg", "picture"), repacked);
    }

    @Test
    void aZippedIndexIsUnpackedIntoTheIndexFolderWhicheverWayItsPartsAreNamed() throws IOException {
        byte[] prefixed = zip(parts("Index/Document.iwa", "document", "Index/Tables/DataList.iwa", "list"));
        byte[] bare = zip(parts("Document.iwa", "document", "Tables/DataList.iwa", "list"));

        for (byte[] index : new byte[][] {prefixed, bare}) {
            Map<String, byte[]> folder = new LinkedHashMap<>();
            folder.put("Form.pages/Index.zip", index);
            folder.put("Form.pages/Metadata/Properties.plist", bytes("properties"));

            Map<String, String> repacked = unzip(PagesPackages.repack(zipBytes(folder)));

            assertEquals(parts("Index/Document.iwa", "document", "Index/Tables/DataList.iwa", "list",
                    "Metadata/Properties.plist", "properties"), repacked);
        }
    }

    @Test
    void theRepackedArchiveIsAnOrdinaryZip() throws IOException {
        byte[] repacked = PagesPackages.repack(zip(parts("Form.pages/Index/Document.iwa", "document")));

        assertArrayEquals(new byte[] {0x50, 0x4B, 0x03, 0x04}, Arrays.copyOf(repacked, 4));
    }

    @Test
    void aSingleFolderThatIsNotAPagesFolderIsLeftAsItIs() throws IOException {
        byte[] other = zip(parts("Forms/Index/Document.iwa", "document"));

        assertSame(other, PagesPackages.repack(other));
    }

    @Test
    void whatIsNotAnArchiveIsDamaged() {
        assertDamaged(() -> PagesPackages.repack(bytes("not an archive at all")));
        assertDamaged(() -> PagesPackages.repack(new byte[0]));
    }

    @Test
    void aPartNamedTwiceIsDamaged() throws IOException {
        assertDamaged(() -> PagesPackages.repack(zip(parts("Form.pages/Index/A.iwa", "one", "Form.pages/Index/a.iwa", "two"))));

        Map<String, byte[]> clash = new LinkedHashMap<>();
        clash.put("Form.pages/Index.zip", zip(parts("Index/Document.iwa", "inner")));
        clash.put("Form.pages/Index/Document.iwa", bytes("outer"));
        assertDamaged(() -> PagesPackages.repack(zipBytes(clash)));
    }

    @Test
    void aNameThatClimbsOutOfTheArchiveIsDamaged() throws IOException {
        assertDamaged(() -> PagesPackages.repack(zip(parts("Form.pages/../../outside", "x"))));

        Map<String, byte[]> inner = new LinkedHashMap<>();
        inner.put("Form.pages/Index.zip", zip(parts("../Document.iwa", "x")));
        assertDamaged(() -> PagesPackages.repack(zipBytes(inner)));
    }

    @Test
    void theInnerArchiveCountsAgainstTheSameBounds() throws IOException {
        Map<String, byte[]> manyInside = new LinkedHashMap<>();
        manyInside.put("Form.pages/Index.zip", zip(parts("Index/A.iwa", "a", "Index/B.iwa", "b", "Index/C.iwa", "c")));
        manyInside.put("Form.pages/preview.jpg", bytes("picture"));
        assertDamaged(() -> PagesPackages.repack(zipBytes(manyInside), 1_000_000, 4));

        Map<String, byte[]> largeInside = new LinkedHashMap<>();
        largeInside.put("Form.pages/Index.zip", zip(parts("Index/Document.iwa", "x".repeat(5_000))));
        assertDamaged(() -> PagesPackages.repack(zipBytes(largeInside), 4_000, 100));
    }

    @Test
    void aPackageThatExpandsImplausiblyFarForItsSizeIsDamaged() throws IOException {
        Map<String, byte[]> bomb = new LinkedHashMap<>();
        bomb.put("Index/Document.iwa", new byte[9 * 1024 * 1024]);

        DocumentConversionException refused = assertDamaged(() -> PagesPackages.repack(zipBytes(bomb)));
        assertTrue(refused.getMessage().contains("implausibly"));
    }

    private static DocumentConversionException assertDamaged(org.junit.jupiter.api.function.Executable repack) {
        DocumentConversionException refused = assertThrows(DocumentConversionException.class, repack);
        assertEquals(DocumentConversionException.Reason.DAMAGED, refused.reason());
        return refused;
    }

    private static Map<String, String> parts(String... nameThenContent) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (int i = 0; i < nameThenContent.length; i += 2) {
            parts.put(nameThenContent[i], nameThenContent[i + 1]);
        }
        return parts;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, String> parts) throws IOException {
        Map<String, byte[]> binary = new LinkedHashMap<>();
        parts.forEach((name, content) -> binary.put(name, bytes(content)));
        return zipBytes(binary);
    }

    private static byte[] zipBytes(Map<String, byte[]> parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, String> unzip(byte[] archive) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
