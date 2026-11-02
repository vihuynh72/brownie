package io.github.vihuynh72.brownie.api.form;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * What a search of the catalog answers: matches on the words in a title,
 * the agency's name and the entry's keywords, in the file's own order, and
 * never more than twenty rows.
 */
class FormLibraryCatalogTest {

    private final FormLibraryCatalog catalog = FormLibraryCatalog.load(new ObjectMapper());

    @Test
    void anEmptyQueryAnswersTheCatalogInItsOwnOrder() {
        var results = catalog.search(null);
        assertThat(results).hasSize(20);
        assertThat(results.get(0).id()).isEqualTo("irs-w9");
    }

    @Test
    void aQueryMatchesTitlesAgenciesAndKeywordsWithoutCaringAboutCase() {
        assertThat(catalog.search("w-9").stream().map(FormLibraryCatalog.Entry::id)).contains("irs-w9");
        assertThat(catalog.search("VETERANS").stream().map(FormLibraryCatalog.Entry::id))
                .contains("va-21-4138", "va-21-526ez");
        assertThat(catalog.search("passport").stream().map(FormLibraryCatalog.Entry::id)).contains("state-ds11");
        assertThat(catalog.search("medicare").stream().map(FormLibraryCatalog.Entry::id))
                .contains("cms-40b", "cms-l564", "cms-1696");
        assertThat(catalog.search("ein").stream().map(FormLibraryCatalog.Entry::id)).contains("irs-ss4");
    }

    @Test
    void aQueryNothingMentionsAnswersEmpty() {
        assertThat(catalog.search("xylophone")).isEmpty();
    }

    @Test
    void findKnowsAnIdAndMissesEveryOther() {
        assertThat(catalog.find("irs-w9")).isPresent();
        assertThat(catalog.find("no-such-form")).isEmpty();
    }
}
