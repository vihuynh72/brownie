package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The library's text reader, made to charge a {@link PdfReadingBudget}
 * for each thing it is about to open, at the point where it opens it: a
 * page's content when the page begins, a form each time one is drawn, a
 * font when an operator sets it. Everything that reads the text of a PDF
 * nobody here wrote goes through this, so that an upload and a rendered
 * file read back from the renderer are held to the same limits.
 */
public class BoundedPdfTextStripper extends PDFTextStripper {

    private final PdfReadingBudget budget;
    /**
     * The library keeps a font that is an object of its own for the whole
     * document, but builds one written directly into a resource dictionary
     * again for every content stream that sets it. These are the names
     * already built for the stream being read now.
     */
    private Set<COSName> fontsBuiltForThisStream = new HashSet<>();

    public BoundedPdfTextStripper(PdfReadingBudget budget) {
        this.budget = budget;
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        // The library walks every page and reads only those in range; only those are charged.
        if (getCurrentPageNo() >= getStartPage() && getCurrentPageNo() <= getEndPage()) {
            budget.startPage();
            fontsBuiltForThisStream = new HashSet<>();
            Iterator<PDStream> contents = page.getContentStreams();
            while (contents.hasNext()) {
                budget.charge(contents.next().getCOSObject());
            }
        }
        super.processPage(page);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        budget.charge(form.getCOSObject());
        Set<COSName> outer = fontsBuiltForThisStream;
        fontsBuiltForThisStream = new HashSet<>();
        try {
            super.showForm(form);
        } finally {
            fontsBuiltForThisStream = outer;
        }
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        budget.charge(form.getCOSObject());
        Set<COSName> outer = fontsBuiltForThisStream;
        fontsBuiltForThisStream = new HashSet<>();
        try {
            super.showTransparencyGroup(form);
        } finally {
            fontsBuiltForThisStream = outer;
        }
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (!operands.isEmpty() && operands.get(0) instanceof COSName name) {
            if (OperatorName.SET_FONT_AND_SIZE.equals(operator.getName())) {
                chargeFontNamed(name);
            } else if (OperatorName.SET_GRAPHICS_STATE_PARAMS.equals(operator.getName())) {
                chargeFontSetByGraphicsState(name);
            }
        }
        super.processOperator(operator, operands);
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        budget.countCharacter();
        super.processTextPosition(text);
    }

    private void chargeFontNamed(COSName name) {
        COSDictionary fonts = resourcesOfKind(COSName.FONT);
        COSBase font = fonts == null ? null : fonts.getItem(name);
        if (font instanceof COSObject reference) {
            budget.chargeFontKeptForTheDocument(reference.getObject());
        } else if (font != null && fontsBuiltForThisStream.add(name)) {
            budget.chargeFont(font);
        }
    }

    /** A graphics state may set a font too, and the library builds that one afresh every time the state is applied. */
    private void chargeFontSetByGraphicsState(COSName name) {
        COSDictionary states = resourcesOfKind(COSName.EXT_G_STATE);
        COSDictionary state = states == null ? null : states.getCOSDictionary(name);
        COSArray fontAndSize = state == null ? null : state.getCOSArray(COSName.FONT);
        if (fontAndSize != null && fontAndSize.size() > 0) {
            budget.chargeFont(fontAndSize.get(0));
        }
    }

    private COSDictionary resourcesOfKind(COSName kind) {
        PDResources resources = getResources();
        return resources == null ? null : resources.getCOSObject().getCOSDictionary(kind);
    }
}
