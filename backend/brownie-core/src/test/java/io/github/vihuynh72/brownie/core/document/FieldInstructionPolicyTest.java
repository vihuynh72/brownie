package io.github.vihuynh72.brownie.core.document;

import io.github.vihuynh72.brownie.core.document.FieldInstructionPolicy.Treatment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldInstructionPolicyTest {

    @Test
    void fieldsWordWorksOutFromTheDocumentItselfAreKept() {
        for (String instruction : List.of(
                " PAGE ", "PAGE \\* MERGEFORMAT", "NUMPAGES", "SECTION", "SECTIONPAGES",
                "DATE \\@ \"d MMMM yyyy\"", "TIME", "CREATEDATE", "SAVEDATE", "PRINTDATE",
                "TOC \\o \"1-3\" \\h \\z \\u", "TC \"Entry\"", "XE \"term\"", "INDEX \\c \"2\"",
                "REF _Ref123 \\h", "PAGEREF _Toc1 \\h", "NOTEREF _Ref9", "SEQ Figure \\* ARABIC", "STYLEREF \"Heading 1\"",
                "HYPERLINK \"https://example.com/\"", "SYMBOL 183 \\f Symbol", "EQ \\f(1,2)", "=SUM(ABOVE)", "= 2 + 2",
                "IF 1 = 1 \"yes\" \"no\"", "QUOTE \"text\"", "DOCPROPERTY Company", "NUMWORDS", "NUMCHARS", "LISTNUM",
                "AUTONUM", "AUTONUMLGL", "AUTONUMOUT", "page")) {
            assertEquals(Treatment.KEEP, FieldInstructionPolicy.classify(instruction), instruction);
        }
    }

    @Test
    void aFormsOwnBlanksBecomeFillSpots() {
        for (String instruction : List.of(
                "FORMTEXT", " FORMDROPDOWN ", "MERGEFIELD client.name \\* MERGEFORMAT", "FILLIN \"Your name?\"",
                "ASK name \"Your name?\"", "MACROBUTTON NoMacro [Click here and type]")) {
            assertEquals(Treatment.TO_SPOT, FieldInstructionPolicy.classify(instruction), instruction);
        }
        assertEquals(Treatment.CHECKBOX, FieldInstructionPolicy.classify(" FORMCHECKBOX "));
    }

    /** Fields that reach outside the file, and every field not named, are frozen to the text they show. */
    @Test
    void fieldsThatReachOutsideAndUnknownFieldsAreFrozen() {
        for (String instruction : List.of(
                "DDE Excel \"C:\\\\data.xlsx\" \"R1C1\"", "DDEAUTO c:\\\\windows\\\\system32\\\\cmd.exe \"/k calc.exe\"",
                "INCLUDETEXT \"C:\\\\a.docx\"", "INCLUDEPICTURE \"https://example.com/p.png\" \\d", "LINK Excel.Sheet.8 \"a.xls\"",
                "IMPORT \"a.png\"", "DATABASE \\d \"a.mdb\"", "USERNAME", "USERINITIALS", "USERADDRESS", "AUTOTEXT Sig",
                "ADDIN Mendeley", "EMBED Excel.Sheet.12", "FILENAME \\p", "GOTOBUTTON x y", "MADEUPFIELD", "", "   ",
                "\\* MERGEFORMAT", "\"quoted\"")) {
            assertEquals(Treatment.FREEZE, FieldInstructionPolicy.classify(instruction), instruction);
        }
        assertEquals(Treatment.FREEZE, FieldInstructionPolicy.classify(null));
    }

    @Test
    void theKeywordIsTheFirstWordInUpperCaseOrTheFormulaSign() {
        assertEquals("PAGE", FieldInstructionPolicy.keyword("  page\\* MERGEFORMAT "));
        assertEquals("MERGEFIELD", FieldInstructionPolicy.keyword("MERGEFIELD\tname"));
        assertEquals("=", FieldInstructionPolicy.keyword(" =SUM(ABOVE)"));
        assertEquals("", FieldInstructionPolicy.keyword("\"quoted\""));
        assertEquals("", FieldInstructionPolicy.keyword(null));
    }
}
