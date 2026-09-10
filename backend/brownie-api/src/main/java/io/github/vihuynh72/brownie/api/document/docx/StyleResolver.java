package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.ResolvedStyle;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDocDefaults;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTOnOff;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrBase;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrDefault;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPrDefault;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves formatting the way Word actually applies it: a run or
 * paragraph's own direct properties first, then the paragraph's referenced
 * style and that style's {@code basedOn} ancestors (nearest first), then
 * the style part's document-wide defaults. Each property is taken from the
 * first layer in that chain that actually sets it -- not a full OOXML
 * toggle-XOR implementation, an intentional, documented simplification
 * (see {@link ResolvedStyle}).
 */
final class StyleResolver {

    private StyleResolver() {
    }

    /** {@code directRPr} is the run's own {@code w:rPr}, or null if it has none. */
    static ResolvedStyle resolveRun(CTRPr directRPr, XWPFParagraph paragraph, XWPFStyles styles) {
        List<CTRPr> chain = new ArrayList<>();
        if (directRPr != null) {
            chain.add(directRPr);
        }
        collectRunPropertyChain(paragraph.getStyleID(), styles, chain);
        CTRPr docDefault = docDefaultRunProperties(styles);
        if (docDefault != null) {
            chain.add(docDefault);
        }

        Boolean bold = firstToggle(chain, CTRPr::sizeOfBArray, CTRPr::getBArray);
        Boolean italic = firstToggle(chain, CTRPr::sizeOfIArray, CTRPr::getIArray);
        Boolean underline = firstUnderline(chain);
        String fontFamily = firstFontFamily(chain);
        Integer fontSizeHalfPoints = firstFontSize(chain);
        String colorHex = firstColor(chain);
        return new ResolvedStyle(bold, italic, underline, fontFamily, fontSizeHalfPoints, colorHex, null, null, null);
    }

    /** Paragraph-level formatting: alignment and any direct numbering reference. Runs never carry these. */
    static ResolvedStyle resolveParagraph(XWPFParagraph paragraph, XWPFStyles styles) {
        List<CTPPrBase> chain = new ArrayList<>();
        CTPPr direct = paragraph.getCTP().getPPr();
        if (direct != null) {
            chain.add(direct);
        }
        collectParagraphPropertyChain(paragraph.getStyleID(), styles, chain);
        CTPPrBase docDefault = docDefaultParagraphProperties(styles);
        if (docDefault != null) {
            chain.add(docDefault);
        }

        String alignment = firstAlignment(chain);
        Integer numberingId = direct != null && direct.isSetNumPr() ? intValue(direct.getNumPr().getNumId()) : null;
        Integer numberingLevel = numberingId == null
                ? null
                : (direct.getNumPr().isSetIlvl() ? intValue(direct.getNumPr().getIlvl()) : 0);
        return new ResolvedStyle(null, null, null, null, null, null, alignment, numberingId, numberingLevel);
    }

    private static void collectRunPropertyChain(String styleId, XWPFStyles styles, List<CTRPr> chain) {
        Set<String> visited = new HashSet<>();
        while (styleId != null && visited.add(styleId)) {
            XWPFStyle style = styles == null ? null : styles.getStyle(styleId);
            if (style == null) {
                return;
            }
            CTStyle ctStyle = style.getCTStyle();
            if (ctStyle.isSetRPr()) {
                chain.add(ctStyle.getRPr());
            }
            styleId = style.getBasisStyleID();
        }
    }

    private static void collectParagraphPropertyChain(String styleId, XWPFStyles styles, List<CTPPrBase> chain) {
        Set<String> visited = new HashSet<>();
        while (styleId != null && visited.add(styleId)) {
            XWPFStyle style = styles == null ? null : styles.getStyle(styleId);
            if (style == null) {
                return;
            }
            CTStyle ctStyle = style.getCTStyle();
            if (ctStyle.isSetPPr()) {
                chain.add(ctStyle.getPPr());
            }
            styleId = style.getBasisStyleID();
        }
    }

    private static CTRPr docDefaultRunProperties(XWPFStyles styles) {
        if (styles == null || styles.getCtStyles() == null || !styles.getCtStyles().isSetDocDefaults()) {
            return null;
        }
        CTDocDefaults docDefaults = styles.getCtStyles().getDocDefaults();
        if (!docDefaults.isSetRPrDefault()) {
            return null;
        }
        CTRPrDefault rPrDefault = docDefaults.getRPrDefault();
        return rPrDefault.isSetRPr() ? rPrDefault.getRPr() : null;
    }

    private static CTPPrBase docDefaultParagraphProperties(XWPFStyles styles) {
        if (styles == null || styles.getCtStyles() == null || !styles.getCtStyles().isSetDocDefaults()) {
            return null;
        }
        CTDocDefaults docDefaults = styles.getCtStyles().getDocDefaults();
        if (!docDefaults.isSetPPrDefault()) {
            return null;
        }
        CTPPrDefault pPrDefault = docDefaults.getPPrDefault();
        return pPrDefault.isSetPPr() ? pPrDefault.getPPr() : null;
    }

    /**
     * A bare toggle element (for example {@code <w:b/>}) with no {@code
     * w:val} means "on" -- {@code CTOnOff.getVal()} returns null in that
     * exact case, distinct from an explicit {@code w:val="false"}, which
     * returns {@code Boolean.FALSE}. Confirmed empirically against real
     * generated and reparsed XML, not assumed from the schema alone.
     */
    private interface ToggleCount<T> {
        int sizeOf(T rPr);
    }

    private interface ToggleArray<T> {
        CTOnOff get(T rPr, int index);
    }

    private static Boolean firstToggle(List<CTRPr> chain, ToggleCount<CTRPr> count, ToggleArray<CTRPr> array) {
        for (CTRPr rPr : chain) {
            if (count.sizeOf(rPr) > 0) {
                CTOnOff toggle = array.get(rPr, 0);
                return !toggle.isSetVal() || Boolean.TRUE.equals(toggle.getVal());
            }
        }
        return null;
    }

    private static Boolean firstUnderline(List<CTRPr> chain) {
        for (CTRPr rPr : chain) {
            if (rPr.sizeOfUArray() > 0) {
                Object val = rPr.getUArray(0).getVal();
                return val != null && !"none".equalsIgnoreCase(String.valueOf(val));
            }
        }
        return null;
    }

    private static String firstFontFamily(List<CTRPr> chain) {
        for (CTRPr rPr : chain) {
            if (rPr.sizeOfRFontsArray() > 0) {
                String ascii = rPr.getRFontsArray(0).getAscii();
                if (ascii != null) {
                    return ascii;
                }
            }
        }
        return null;
    }

    private static Integer firstFontSize(List<CTRPr> chain) {
        for (CTRPr rPr : chain) {
            if (rPr.sizeOfSzArray() > 0) {
                Object val = rPr.getSzArray(0).getVal();
                if (val instanceof BigInteger big) {
                    return big.intValue();
                }
                if (val != null) {
                    return Integer.parseInt(val.toString());
                }
            }
        }
        return null;
    }

    private static String firstColor(List<CTRPr> chain) {
        for (CTRPr rPr : chain) {
            if (rPr.sizeOfColorArray() > 0) {
                Object val = rPr.getColorArray(0).getVal();
                if (val != null) {
                    return String.valueOf(val);
                }
            }
        }
        return null;
    }

    private static String firstAlignment(List<CTPPrBase> chain) {
        for (CTPPrBase pPr : chain) {
            if (pPr.isSetJc()) {
                CTJc jc = pPr.getJc();
                if (jc.getVal() != null) {
                    return jc.getVal().toString();
                }
            }
        }
        return null;
    }

    private static Integer intValue(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber number) {
        return number == null ? null : number.getVal().intValue();
    }
}
