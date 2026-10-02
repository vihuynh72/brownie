package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.util.Matrix;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds PDFs that are forms, or that a form reader must refuse, for tests:
 * fillable forms with every kind of field, flat forms whose blanks are
 * drawn or typed, turned pages, a crop box that does not start at zero,
 * scans, and files that are locked, signed, XFA, or do things on their
 * own. Like {@link PdfFixtures}, every fixture is written through {@link
 * PDDocument#save}, so tests read exactly the bytes an upload would carry.
 *
 * <p>Where a test needs to know where something was put, the coordinates
 * are constants here, in the PDF's own user space (Y up).
 */
public final class PdfFormFixtures {

    /** The name field's widget in {@link #fillableForm()}: user space x, y, width, height. */
    public static final float[] NAME_WIDGET = {150, 690, 300, 20};
    public static final float[] ADDRESS_WIDGET = {150, 600, 300, 60};
    public static final float[] ZIP_WIDGET = {150, 560, 100, 20};
    public static final float[] REFERENCE_WIDGET = {150, 520, 200, 20};
    public static final float[] EMAIL_WIDGET = {150, 480, 300, 20};
    public static final float[] BIRTH_DATE_WIDGET = {150, 440, 150, 20};
    public static final float[] PHONE_WIDGET = {150, 400, 150, 20};

    private PdfFormFixtures() {
    }

    // ---- fillable forms ----

    /**
     * One Letter page with labels printed beside a fillable form's fields:
     * a one-line name with a description, a multi-line address, a
     * five-character comb for a postcode, a read-only reference that
     * already holds a value, a required email, a date of birth whose
     * formatting script names its format, a phone number inside a group
     * ("applicant.phone"), a check box, a pair of radio buttons, a list to
     * choose from, and an empty signature field.
     */
    public static byte[] fillableForm() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 12, 72, 740, "Membership application");
                writeLine(cs, helvetica(), 11, 72, 696, "Full name:");
                writeLine(cs, helvetica(), 11, 72, 646, "Address:");
                writeLine(cs, helvetica(), 11, 72, 566, "Postcode:");
                writeLine(cs, helvetica(), 11, 72, 526, "Reference:");
                writeLine(cs, helvetica(), 11, 72, 486, "Email:");
                writeLine(cs, helvetica(), 11, 72, 446, "Date of birth:");
                writeLine(cs, helvetica(), 11, 72, 406, "Phone:");
                writeLine(cs, helvetica(), 11, 72, 366, "Send me news:");
                writeLine(cs, helvetica(), 11, 72, 326, "Contact by:");
                writeLine(cs, helvetica(), 11, 72, 286, "Country:");
                writeLine(cs, helvetica(), 11, 72, 246, "Signature:");
            }
            PDTextField name = textField(form, page, "fullName", NAME_WIDGET, "/Helv 11 Tf 0 g");
            name.setAlternateFieldName("Full name");
            PDTextField address = textField(form, page, "address", ADDRESS_WIDGET, "/Helv 10 Tf 0 g");
            address.setMultiline(true);
            PDTextField zip = textField(form, page, "zip", ZIP_WIDGET, "/Cour 12 Tf 0 g");
            zip.setMaxLen(5);
            zip.setComb(true);
            PDTextField reference = textField(form, page, "reference", REFERENCE_WIDGET, "/Helv 10 Tf 0 g");
            reference.setValue("REF-001");
            reference.setReadOnly(true);
            PDTextField email = textField(form, page, "email", EMAIL_WIDGET, "/Helv 0 Tf 0 g");
            email.setRequired(true);
            PDTextField birthDate = textField(form, page, "birthDate", BIRTH_DATE_WIDGET, "/Helv 11 Tf 0 g");
            birthDate.getCOSObject().setItem(COSName.AA, formatScript("AFDate_FormatEx(\"mm/dd/yyyy\");"));

            PDNonTerminalField applicant = new PDNonTerminalField(form);
            applicant.setPartialName("applicant");
            PDTextField phone = new PDTextField(form);
            phone.setPartialName("phone");
            phone.setDefaultAppearance("/Helv 11 Tf 0 g");
            placeWidget(phone.getWidgets().get(0), page, PHONE_WIDGET);
            phone.getCOSObject().setItem(COSName.PARENT, applicant.getCOSObject());
            applicant.setChildren(List.of(phone));
            form.getFields().add(applicant);

            PDCheckBox news = new PDCheckBox(form);
            news.setPartialName("news");
            placeWidget(news.getWidgets().get(0), page, new float[] {150, 360, 14, 14});
            form.getFields().add(news);

            PDRadioButton contact = new PDRadioButton(form);
            contact.setPartialName("contactBy");
            List<PDAnnotationWidget> options = new ArrayList<>();
            for (float x : new float[] {150, 250}) {
                PDAnnotationWidget option = new PDAnnotationWidget();
                placeWidget(option, page, new float[] {x, 320, 14, 14});
                option.getCOSObject().setItem(COSName.PARENT, contact.getCOSObject());
                options.add(option);
            }
            contact.setWidgets(options);
            form.getFields().add(contact);

            PDComboBox country = new PDComboBox(form);
            country.setPartialName("country");
            country.setOptions(List.of("Canada", "Viet Nam"));
            placeWidget(country.getWidgets().get(0), page, new float[] {150, 280, 150, 20});
            form.getFields().add(country);

            PDSignatureField signature = new PDSignatureField(form);
            signature.setPartialName("signature");
            placeWidget(signature.getWidgets().get(0), page, new float[] {150, 230, 200, 30});
            form.getFields().add(signature);
            return write(doc);
        }
    }

    /** The widget of the name field in {@link #fieldOverPrintedBlank()}, laid over the printed underscores. */
    public static final float[] OVER_BLANK_WIDGET = {125, 693, 250, 16};

    /**
     * A form whose name field sits over the printed blank it stands for
     * ("Full name: ____"), as a form maker's automatic field detection puts
     * it: the underscores are page text inside the field's rectangle.
     */
    public static byte[] fieldOverPrintedBlank() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 11, 72, 696, "Full name: " + "_".repeat(30));
            }
            textField(form, page, "fullName", OVER_BLANK_WIDGET, "/Helv 11 Tf 0 g");
            return write(doc);
        }
    }

    /** A one-line name field shown in two places: twice on one page, or once on each of two pages. */
    public static byte[] fieldShownTwice(boolean onTwoPages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.LETTER);
            doc.addPage(first);
            PDPage second = first;
            if (onTwoPages) {
                second = new PDPage(PDRectangle.LETTER);
                doc.addPage(second);
            }
            PDAcroForm form = newForm(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, first)) {
                writeLine(cs, helvetica(), 11, 72, 696, "Full name:");
            }
            PDTextField name = new PDTextField(form);
            name.setPartialName("fullName");
            name.setDefaultAppearance("/Helv 11 Tf 0 g");
            List<PDAnnotationWidget> widgets = new ArrayList<>();
            for (PDPage page : List.of(first, second)) {
                PDAnnotationWidget widget = new PDAnnotationWidget();
                placeWidget(widget, page, page == first && widgets.isEmpty() ? NAME_WIDGET : new float[] {150, 100, 300, 20});
                widget.getCOSObject().setItem(COSName.PARENT, name.getCOSObject());
                widgets.add(widget);
            }
            name.setWidgets(widgets);
            form.getFields().add(name);
            return write(doc);
        }
    }

    /**
     * A form with fields no reader draws: "explain", hidden until something
     * shows it; "nowhere", a widget with no room at all; and "fullName",
     * shown once and kept hidden in a second place.
     */
    public static byte[] fieldsNobodySees() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 11, 72, 696, "Full name:");
            }
            textField(form, page, "explain", new float[] {150, 600, 300, 20}, "/Helv 11 Tf 0 g").getWidgets().get(0).setHidden(true);
            textField(form, page, "nowhere", new float[] {150, 560, 0, 0}, "/Helv 11 Tf 0 g");
            PDTextField name = new PDTextField(form);
            name.setPartialName("fullName");
            name.setDefaultAppearance("/Helv 11 Tf 0 g");
            List<PDAnnotationWidget> widgets = new ArrayList<>();
            for (float[] rect : new float[][] {NAME_WIDGET, {150, 400, 300, 20}}) {
                PDAnnotationWidget widget = new PDAnnotationWidget();
                placeWidget(widget, page, rect);
                widget.getCOSObject().setItem(COSName.PARENT, name.getCOSObject());
                widgets.add(widget);
            }
            widgets.get(1).setHidden(true);
            name.setWidgets(widgets);
            form.getFields().add(name);
            return write(doc);
        }
    }

    /** A form whose signature field already holds a signature. */
    public static byte[] signedForm() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            textField(form, page, "fullName", NAME_WIDGET, "/Helv 11 Tf 0 g");
            PDSignatureField signature = new PDSignatureField(form);
            signature.setPartialName("signature");
            placeWidget(signature.getWidgets().get(0), page, new float[] {150, 230, 200, 30});
            COSDictionary value = new COSDictionary();
            value.setItem(COSName.TYPE, COSName.SIG);
            value.setItem(COSName.FILTER, COSName.getPDFName("Adobe.PPKLite"));
            value.setItem(COSName.SUB_FILTER, COSName.getPDFName("adbe.pkcs7.detached"));
            value.setItem(COSName.CONTENTS, new COSString(new byte[16]));
            signature.getCOSObject().setItem(COSName.V, value);
            form.getFields().add(signature);
            return write(doc);
        }
    }

    /** A form that also carries an XFA description; a dynamic one draws its pages itself and has no fields of its own. */
    public static byte[] xfaForm(boolean dynamic) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            if (!dynamic) {
                textField(form, page, "fullName", NAME_WIDGET, "/Helv 11 Tf 0 g");
            } else {
                doc.getDocumentCatalog().getCOSObject().setBoolean(COSName.getPDFName("NeedsRendering"), true);
            }
            COSStream xdp = doc.getDocument().createCOSStream();
            try (OutputStream out = xdp.createOutputStream()) {
                out.write("<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\"><template/></xdp:xdp>".getBytes(StandardCharsets.US_ASCII));
            }
            form.getCOSObject().setItem(COSName.XFA, xdp);
            return write(doc);
        }
    }

    /** A form a hundred-odd fields larger than {@code count} allows, for the cap on fields. */
    public static byte[] formWithTextFields(int count) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            for (int index = 0; index < count; index++) {
                float x = 20 + (index % 20) * 29;
                float y = 20 + (index / 20 % 50) * 15;
                textField(form, page, "f" + index, new float[] {x, y, 25, 12}, "/Helv 8 Tf 0 g");
            }
            return write(doc);
        }
    }

    /** A form on a page turned by {@code rotation}, its field's widget turned the same way so its text reads upright. */
    public static byte[] fillableFormOnTurnedPage(int rotation) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.setRotation(rotation);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            PDTextField name = textField(form, page, "fullName", NAME_WIDGET, "/Helv 11 Tf 0 g");
            if (rotation != 0) {
                PDAppearanceCharacteristicsDictionary characteristics = name.getWidgets().get(0).getAppearanceCharacteristics();
                characteristics.getCOSObject().setInt(COSName.R, rotation);
                float[] turned = rotation % 180 == 0 ? NAME_WIDGET : new float[] {150, 400, 20, 300};
                name.getWidgets().get(0).setRectangle(new PDRectangle(turned[0], turned[1], turned[2], turned[3]));
            }
            return write(doc);
        }
    }

    // ---- files a form reader refuses ----

    /** Opens without a password, but an owner password restricts what may be done with it. */
    public static byte[] ownerPasswordOnlyForm() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDAcroForm form = newForm(doc);
            textField(form, page, "fullName", NAME_WIDGET, "/Helv 11 Tf 0 g");
            AccessPermission permissions = new AccessPermission();
            permissions.setCanModify(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-only", "", permissions);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            return write(doc);
        }
    }

    /** A link on the page whose action starts another program. */
    public static byte[] launchActionDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 12, 72, 720, "Click here to continue.");
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 715, 150, 15));
            COSDictionary launch = new COSDictionary();
            launch.setItem(COSName.S, COSName.getPDFName("Launch"));
            launch.setString(COSName.F, "calc.exe");
            link.getCOSObject().setItem(COSName.A, launch);
            page.getAnnotations().add(link);
            return write(doc);
        }
    }

    /** A document carrying a small text file inside it. */
    public static byte[] embeddedFileDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 12, 72, 720, "See the attachment.");
            }
            PDEmbeddedFile file = new PDEmbeddedFile(doc, new ByteArrayInputStream("hello".getBytes(StandardCharsets.US_ASCII)));
            PDComplexFileSpecification specification = new PDComplexFileSpecification();
            specification.setFile("note.txt");
            specification.setEmbeddedFile(file);
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("note.txt", specification));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
            return write(doc);
        }
    }

    /** A document that runs a script when it opens: in its names, or as its open action. */
    public static byte[] documentJavaScript(boolean asOpenAction) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 12, 72, 720, "Welcome.");
            }
            PDActionJavaScript script = new PDActionJavaScript("app.alert('Welcome');");
            if (asOpenAction) {
                doc.getDocumentCatalog().setOpenAction(script);
            } else {
                PDJavascriptNameTreeNode tree = new PDJavascriptNameTreeNode();
                tree.setNames(Map.of("welcome", script));
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
                names.setJavascript(tree);
                doc.getDocumentCatalog().setNames(names);
            }
            return write(doc);
        }
    }

    /** Where a script sits in {@link #annotationScript}. */
    public enum ScriptPlace { WIDGET_PAGE_OPEN, WIDGET_MOUSE_DOWN, SCREEN_PAGE_OPEN, SCREEN_CLICK }

    /**
     * The fillable form with one more script: on its name field's widget,
     * run when the page opens or when the field is clicked, or on a media
     * (Screen) annotation, run when the page opens or when it is clicked.
     */
    public static byte[] annotationScript(ScriptPlace place) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(fillableForm())) {
            COSDictionary script = new COSDictionary();
            script.setItem(COSName.S, COSName.getPDFName("JavaScript"));
            script.setString(COSName.JS, "app.alert('page open');");
            COSDictionary annotation;
            if (place == ScriptPlace.WIDGET_PAGE_OPEN || place == ScriptPlace.WIDGET_MOUSE_DOWN) {
                annotation = doc.getDocumentCatalog().getAcroForm(null).getField("fullName").getCOSObject();
            } else {
                annotation = new COSDictionary();
                annotation.setItem(COSName.TYPE, COSName.ANNOT);
                annotation.setItem(COSName.SUBTYPE, COSName.SCREEN);
                annotation.setItem(COSName.RECT, new PDRectangle(400, 700, 100, 50).getCOSArray());
                doc.getPage(0).getCOSObject().getCOSArray(COSName.ANNOTS).add(annotation);
            }
            if (place == ScriptPlace.SCREEN_CLICK) {
                annotation.setItem(COSName.A, script);
            } else {
                COSDictionary triggers = new COSDictionary();
                triggers.setItem(place == ScriptPlace.WIDGET_MOUSE_DOWN ? COSName.D : COSName.PO, script);
                annotation.setItem(COSName.AA, triggers);
            }
            return write(doc);
        }
    }

    /** Where {@link #pageOpenScriptReachedFirstFrom} has its script reached first. */
    public enum FirstReach { WIDGET_CLICK, WIDGET_KEYSTROKE, OUTLINE_ITEM }

    /**
     * The fillable form with one script object that its name field's widget
     * runs as its page opens, and that is reached first from somewhere a
     * person has to do something: the widget's own click action, its
     * keystroke trigger, or an item of the document's outline.
     */
    public static byte[] pageOpenScriptReachedFirstFrom(FirstReach first) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(fillableForm())) {
            COSDictionary script = new COSDictionary();
            script.setItem(COSName.S, COSName.getPDFName("JavaScript"));
            script.setString(COSName.JS, "app.alert('page open');");
            COSDictionary widget = doc.getDocumentCatalog().getAcroForm(null).getField("fullName").getCOSObject();
            COSDictionary triggers = new COSDictionary();
            switch (first) {
                case WIDGET_CLICK -> widget.setItem(COSName.A, script);
                case WIDGET_KEYSTROKE -> triggers.setItem(COSName.K, script);
                case OUTLINE_ITEM -> {
                    COSDictionary outlines = outlineOf(1);
                    outlines.getCOSDictionary(COSName.FIRST).setItem(COSName.A, script);
                    doc.getDocumentCatalog().getCOSObject().setItem(COSName.OUTLINES, outlines);
                }
            }
            triggers.setItem(COSName.PO, script);
            widget.setItem(COSName.AA, triggers);
            return write(doc);
        }
    }

    /** The fillable form with an outline of {@code items} links to a web page, and a script its name field's widget runs as its page opens. */
    public static byte[] longOutlineThenPageOpenScript(int items) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(fillableForm())) {
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.OUTLINES, outlineOf(items));
            COSDictionary script = new COSDictionary();
            script.setItem(COSName.S, COSName.getPDFName("JavaScript"));
            script.setString(COSName.JS, "app.alert('page open');");
            COSDictionary triggers = new COSDictionary();
            triggers.setItem(COSName.PO, script);
            doc.getDocumentCatalog().getAcroForm(null).getField("fullName").getCOSObject().setItem(COSName.AA, triggers);
            return write(doc);
        }
    }

    /** An outline of {@code items} entries in a row, each a link to a web page. */
    private static COSDictionary outlineOf(int items) {
        COSDictionary outlines = new COSDictionary();
        outlines.setItem(COSName.TYPE, COSName.OUTLINES);
        COSDictionary previous = null;
        for (int index = 0; index < items; index++) {
            COSDictionary item = new COSDictionary();
            item.setString(COSName.TITLE, "Section " + (index + 1));
            item.setItem(COSName.PARENT, outlines);
            COSDictionary link = new COSDictionary();
            link.setItem(COSName.S, COSName.URI);
            link.setString(COSName.URI, "https://example.org/" + (index + 1));
            item.setItem(COSName.A, link);
            if (previous == null) {
                outlines.setItem(COSName.FIRST, item);
            } else {
                previous.setItem(COSName.NEXT, item);
                item.setItem(COSName.PREV, previous);
            }
            previous = item;
        }
        outlines.setItem(COSName.LAST, previous);
        return outlines;
    }

    /** The fillable form with a file carried by its page's associated files ({@code /AF}), where the catalog never mentions it. */
    public static byte[] pageCarryingAFile() throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(fillableForm())) {
            PDEmbeddedFile file = new PDEmbeddedFile(doc, new ByteArrayInputStream("MZ".getBytes(StandardCharsets.US_ASCII)));
            PDComplexFileSpecification specification = new PDComplexFileSpecification();
            specification.setFile("invoice.exe");
            specification.setEmbeddedFile(file);
            org.apache.pdfbox.cos.COSArray associated = new org.apache.pdfbox.cos.COSArray();
            associated.add(specification.getCOSObject());
            doc.getPage(0).getCOSObject().setItem(COSName.getPDFName("AF"), associated);
            return write(doc);
        }
    }

    // ---- flat forms ----

    /**
     * A form with no fillable fields, whose blanks are typed or drawn:
     * underscores hard against a label, a dot leader, a label with empty
     * space after it, labels on drawn lines, a table with empty cells, and
     * a signature line. It also holds what only looks like a blank: a
     * heading with a line drawn under it, a line across the whole page, and
     * a label followed closely by more text.
     */
    public static byte[] flatForm() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont regular = helvetica();
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont times = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, bold, 14, 72, 740, "Volunteer sign-up");
                line(cs, 72, 736, 212, 736);
                writeLine(cs, regular, 11, 72, 700, "Full name:______________________________");
                writeLine(cs, regular, 11, 72, 670, "Date of birth: ..............................");
                writeLine(cs, regular, 11, 72, 640, "Email:");
                writeLine(cs, regular, 11, 72, 610, "Note: see the back of this page for details.");
                writeLine(cs, times, 12, 72, 580, "Address:");
                line(cs, 130, 578, 500, 578);
                writeLine(cs, regular, 11, 72, 520, "Course");
                writeLine(cs, regular, 11, 222, 520, "Year");
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 2; column++) {
                        cs.addRect(72 + column * 150, 490 - row * 25, 150, 25);
                    }
                }
                cs.stroke();
                writeLine(cs, regular, 11, 76, 497, "Painting");
                writeLine(cs, regular, 11, 72, 380, "Signature:");
                line(cs, 140, 378, 340, 378);
                line(cs, 40, 100, 572, 100);
                writeLine(cs, regular, 9, 72, 88, "Please return this form to the front desk.");
            }
            return write(doc);
        }
    }

    /**
     * A flat page turned by {@code rotation}, whose text is set turned the
     * same way so that it reads upright on screen: a label with a line to
     * write on after it.
     */
    public static byte[] flatFormOnTurnedPage(int rotation) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.setRotation(rotation);
            doc.addPage(page);
            PDRectangle box = page.getMediaBox();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Everything is drawn in the frame the viewer shows, then turned into the page's own frame.
                cs.transform(uprightFrame(rotation, box.getWidth(), box.getHeight()));
                PDFont font = helvetica();
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, displayedHeight(rotation, box) - 100);
                cs.showText("Full name:");
                cs.endText();
                line(cs, 140, displayedHeight(rotation, box) - 102, 400, displayedHeight(rotation, box) - 102);
            }
            return write(doc);
        }
    }

    /** The transform from the displayed frame (origin bottom-left as shown, Y up) of a page turned by {@code rotation} into its own. */
    static Matrix uprightFrame(int rotation, float width, float height) {
        return switch (rotation) {
            case 90 -> new Matrix(0, 1, -1, 0, width, 0);
            case 180 -> new Matrix(-1, 0, 0, -1, width, height);
            case 270 -> new Matrix(0, -1, 1, 0, 0, height);
            default -> new Matrix();
        };
    }

    private static float displayedHeight(int rotation, PDRectangle box) {
        return rotation % 180 == 0 ? box.getHeight() : box.getWidth();
    }

    /**
     * A page whose crop box starts at (36, 50) and is 540 by 692: text at
     * user (100, 700), a rectangle at user (100, 600) 200 by 20, and a line
     * from user (100, 500) to (300, 500).
     */
    public static byte[] offsetCropBoxDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.setCropBox(new PDRectangle(36, 50, 540, 692));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 12, 100, 700, "Crop origin");
                cs.addRect(100, 600, 200, 20);
                cs.stroke();
                line(cs, 100, 500, 300, 500);
            }
            return write(doc);
        }
    }

    /** One line of text in Liberation Sans embedded as a subset, whose name therefore carries a six-letter tag. */
    public static byte[] subsetFontDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (InputStream font = PdfFormFixtures.class.getResourceAsStream("/fonts/LiberationSans-Bold.ttf");
                    PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, PDType0Font.load(doc, font, true), 14, 72, 720, "Heading:");
            }
            return write(doc);
        }
    }

    /** A page of one text line whose {@code /UserUnit} is 2. */
    public static byte[] userUnitDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.getCOSObject().setFloat(COSName.getPDFName("UserUnit"), 2f);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeLine(cs, helvetica(), 12, 72, 720, "Large sheet.");
            }
            return write(doc);
        }
    }

    /** One page drawing {@code count} short separate line segments in a single path. */
    public static byte[] manyLinesDocument(int count) throws IOException {
        return manyLinesDocument(1, count);
    }

    /** {@code pages} pages, each drawing {@code count} short separate line segments in a single path. */
    public static byte[] manyLinesDocument(int pages, int count) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int number = 0; number < pages; number++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (int index = 0; index < count; index++) {
                        float y = 50 + (index % 600);
                        cs.moveTo(50, y);
                        cs.lineTo(60, y);
                    }
                    cs.stroke();
                }
            }
            return write(doc);
        }
    }

    /** One page whose content is {@code operators} written {@code times} times over, compressed, and nothing else. */
    public static byte[] pageDrawnWith(String operators, int times) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDStream stream = new PDStream(doc);
            byte[] once = operators.getBytes(StandardCharsets.US_ASCII);
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                for (int index = 0; index < times; index++) {
                    out.write(once);
                }
            }
            page.setContents(stream);
            return write(doc);
        }
    }

    /** One page whose content is split over several streams, each written as given and compressed. */
    public static byte[] pageDrawnWithStreams(String... contents) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.cos.COSArray streams = new org.apache.pdfbox.cos.COSArray();
            for (String content : contents) {
                PDStream stream = new PDStream(doc);
                try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                    out.write(content.getBytes(StandardCharsets.US_ASCII));
                }
                streams.add(stream.getCOSObject());
            }
            page.getCOSObject().setItem(COSName.CONTENTS, streams);
            return write(doc);
        }
    }

    /** One page that draws one form, whose own content is {@code formContent}. */
    public static byte[] pageDrawingAFormOf(String formContent) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(doc);
            form.setBBox(new PDRectangle(10, 10));
            form.setResources(new PDResources());
            try (OutputStream out = form.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
                out.write(formContent.getBytes(StandardCharsets.US_ASCII));
            }
            PDResources resources = new PDResources();
            String name = resources.add(form).getName();
            page.setResources(resources);
            PDStream contents = new PDStream(doc);
            try (OutputStream out = contents.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(("q /" + name + " Do Q\n").getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(contents);
            return write(doc);
        }
    }

    /** One page that draws one small picture {@code times} times, each in its own place. */
    public static byte[] manyPicturesDocument(int times) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDImageXObject picture = LosslessFactory.createFromImage(doc, new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_GRAY));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int index = 0; index < times; index++) {
                    cs.drawImage(picture, 10 + index % 500, 10 + index / 500 * 5f, 2, 2);
                }
            }
            return write(doc);
        }
    }

    /** {@code pages} pages, each covered in {@code wordsPerPage} one-letter words in two-point type. */
    public static byte[] pagesOfTinyWords(int pages, int wordsPerPage) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = helvetica();
            for (int number = 0; number < pages; number++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    int perLine = 250;
                    for (int written = 0, line = 0; written < wordsPerPage; written += perLine, line++) {
                        String text = "a ".repeat(Math.min(perLine, wordsPerPage - written)).trim();
                        writeLine(cs, font, 2, 20, 780 - line * 2.5f, text);
                    }
                }
            }
            return write(doc);
        }
    }

    // ---- scans ----

    /**
     * A page that is one picture and no text, as a scanner makes. With
     * {@code jbig2}, the picture declares the JBIG2 codec scanners use for
     * black-and-white pages (its data is not a real JBIG2 stream: only the
     * declaration matters, since nothing here may decode it).
     */
    public static byte[] scannedPage(boolean jbig2) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            if (jbig2) {
                COSStream picture = doc.getDocument().createCOSStream();
                picture.setItem(COSName.TYPE, COSName.XOBJECT);
                picture.setItem(COSName.SUBTYPE, COSName.IMAGE);
                picture.setInt(COSName.WIDTH, 100);
                picture.setInt(COSName.HEIGHT, 100);
                picture.setInt(COSName.BITS_PER_COMPONENT, 1);
                picture.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
                try (OutputStream out = picture.createRawOutputStream()) {
                    out.write(new byte[] {0, 0, 0, 1, 0, 0, 0, 0});
                }
                picture.setItem(COSName.FILTER, COSName.JBIG2_DECODE);
                PDResources resources = new PDResources();
                COSDictionary xobjects = new COSDictionary();
                xobjects.setItem(COSName.getPDFName("Scan"), picture);
                resources.getCOSObject().setItem(COSName.XOBJECT, xobjects);
                page.setResources(resources);
                COSStream contents = doc.getDocument().createCOSStream();
                try (OutputStream out = contents.createOutputStream()) {
                    out.write("q 612 0 0 792 0 0 cm /Scan Do Q\n".getBytes(StandardCharsets.US_ASCII));
                }
                page.getCOSObject().setItem(COSName.CONTENTS, contents);
            } else {
                PDImageXObject picture = LosslessFactory.createFromImage(doc, scanImage());
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(picture, 0, 0, 612, 792);
                }
            }
            return write(doc);
        }
    }

    /** A grey page with a few dark strokes where handwriting and printed lines would be. */
    static BufferedImage scanImage() {
        BufferedImage image = new BufferedImage(306, 396, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(245, 245, 245));
            graphics.fillRect(0, 0, 306, 396);
            graphics.setColor(Color.DARK_GRAY);
            graphics.setStroke(new BasicStroke(2));
            for (int row = 0; row < 6; row++) {
                graphics.drawLine(40, 60 + row * 40, 266, 60 + row * 40);
            }
            graphics.fillRect(40, 20, 120, 16);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    // ---- building blocks ----

    static PDAcroForm newForm(PDDocument doc) {
        PDAcroForm form = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(form);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("Helv"), helvetica());
        resources.put(COSName.getPDFName("Cour"), new PDType1Font(Standard14Fonts.FontName.COURIER));
        resources.put(COSName.getPDFName("TiRo"), new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN));
        form.setDefaultResources(resources);
        form.setDefaultAppearance("/Helv 0 Tf 0 g");
        return form;
    }

    static PDTextField textField(PDAcroForm form, PDPage page, String name, float[] rect, String defaultAppearance) {
        PDTextField field = new PDTextField(form);
        field.setPartialName(name);
        field.setDefaultAppearance(defaultAppearance);
        placeWidget(field.getWidgets().get(0), page, rect);
        form.getFields().add(field);
        return field;
    }

    static void placeWidget(PDAnnotationWidget widget, PDPage page, float[] rect) {
        widget.setRectangle(new PDRectangle(rect[0], rect[1], rect[2], rect[3]));
        widget.setPage(page);
        widget.setPrinted(true);
        PDAppearanceCharacteristicsDictionary characteristics = new PDAppearanceCharacteristicsDictionary(new COSDictionary());
        characteristics.setBorderColour(new PDColor(new float[] {0.5f}, PDDeviceGray.INSTANCE));
        widget.setAppearanceCharacteristics(characteristics);
        try {
            page.getAnnotations().add(widget);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static COSDictionary formatScript(String script) {
        COSDictionary action = new COSDictionary();
        action.setItem(COSName.S, COSName.getPDFName("JavaScript"));
        action.setString(COSName.JS, script);
        COSDictionary actions = new COSDictionary();
        actions.setItem(COSName.F, action);
        return actions;
    }

    /** Every terminal field of a form, by full name, for tests that look at what a fill did. */
    static List<PDTerminalField> terminalFields(PDAcroForm form) {
        List<PDTerminalField> fields = new ArrayList<>();
        for (PDField field : form.getFieldTree()) {
            if (field instanceof PDTerminalField terminal) {
                fields.add(terminal);
            }
        }
        return fields;
    }

    static PDFont helvetica() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    static void writeLine(PDPageContentStream cs, PDFont font, float size, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    static void line(PDPageContentStream cs, float x0, float y0, float x1, float y1) throws IOException {
        cs.moveTo(x0, y0);
        cs.lineTo(x1, y1);
        cs.stroke();
    }

    static byte[] write(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
