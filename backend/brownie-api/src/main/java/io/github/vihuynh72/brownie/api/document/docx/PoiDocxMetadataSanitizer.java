package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.compile.DocumentSanitizationException;
import io.github.vihuynh72.brownie.core.compile.DocxMetadataSanitizer;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Clears the OOXML metadata this plan names as a real leak risk (author
 * identity, machine/application details, arbitrary custom properties) --
 * comments, tracked changes, and hidden text are not stripped here because
 * template preflight already refuses a source containing them before a
 * template can ever activate, so a filled document produced from an
 * activated template's own working copy cannot have introduced them; this
 * sanitizer's own job is only the metadata a normal fill pass does add
 * (POI itself stamps a creator/application identity when writing).
 */
public final class PoiDocxMetadataSanitizer implements DocxMetadataSanitizer {

    private static final String NEUTRAL_VALUE = "";

    @Override
    public byte[] sanitize(byte[] docxBytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            POIXMLProperties properties = document.getProperties();

            POIXMLProperties.CoreProperties core = properties.getCoreProperties();
            core.setCreator(NEUTRAL_VALUE);
            core.setLastModifiedByUser(NEUTRAL_VALUE);
            core.setDescription(NEUTRAL_VALUE);
            core.setKeywords(NEUTRAL_VALUE);
            core.setSubjectProperty(NEUTRAL_VALUE);

            POIXMLProperties.ExtendedProperties extended = properties.getExtendedProperties();
            extended.setCompany(NEUTRAL_VALUE);
            extended.setManager(NEUTRAL_VALUE);
            extended.setApplication(NEUTRAL_VALUE);
            extended.setAppVersion(NEUTRAL_VALUE);

            properties.getCustomProperties().getUnderlyingProperties().getPropertyList().clear();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new DocumentSanitizationException("Failed to sanitize filled document metadata.", e);
        }
    }
}
