package io.github.vihuynh72.brownie.api.document.docx.prepare;

import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.xmlbeans.XmlObject;

import java.util.List;

/**
 * Lifts the file's editing restrictions (forms-only, read-only,
 * tracked-changes-only, and the "open as read-only" password), which
 * would otherwise stop a person from editing the filled copy in Word.
 */
final class EditingRestrictionRemover {

    private EditingRestrictionRemover() {
    }

    /** Returns how many restrictions were lifted. */
    static int remove(WordPackage word) {
        int removed = 0;
        for (PackagePart settings : word.relatedParts(word.main(), "settings")) {
            List<XmlObject> restrictions = WordXml.elements(word.xml(settings),
                    name -> WordXml.isW(name, "documentProtection") || WordXml.isW(name, "writeProtection"));
            for (XmlObject restriction : restrictions) {
                WordXml.remove(restriction);
                removed++;
            }
        }
        return removed;
    }
}
