package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.artifact.ArtifactContentInspector;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A photo or a scan of a paper form is a thing the form route can take. */
class ImageFormPreparationTest {

    @Test
    void aJpegByItsBytesIsAFormFile() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertEquals(SupportedMediaType.JPEG, inspect(jpeg));
    }

    @Test
    void aPngByItsBytesIsAFormFile() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertEquals(SupportedMediaType.PNG, inspect(png));
    }

    @Test
    void aFileWithAPictureExtensionButNoPictureBytesIsNotAPicture() {
        byte[] notAPicture = "this is just text named photo.png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(SupportedMediaType.PLAIN_TEXT, inspect(notAPicture));
    }

    private static SupportedMediaType inspect(byte[] bytes) {
        try {
            return ArtifactContentInspector.inspect(new java.io.ByteArrayInputStream(bytes));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
