package io.github.vihuynh72.brownie.api.form;

import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The form library's whole table of contents, packaged as {@code
 * form-library/catalog.json} in this module. Every entry names an official
 * form and the one URL it was verified at -- the only URLs the fetcher is
 * ever asked to open. The file's order is the popularity order an empty
 * search answers with.
 */
public record FormLibraryCatalog(List<Entry> entries) {

    public record Entry(
            String id,
            String title,
            String agency,
            String category,
            String description,
            String url,
            List<String> keywords) {
    }

    /** The at-most-20 answers a search gets, in catalog order. */
    private static final int LIMIT = 20;
    private static final String RESOURCE = "/form-library/catalog.json";

    public static FormLibraryCatalog load(ObjectMapper objectMapper) {
        try (InputStream in = FormLibraryCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return new FormLibraryCatalog(
                    objectMapper.readValue(in, new tools.jackson.core.type.TypeReference<List<Entry>>() {}));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(RESOURCE + " could not be read", e);
        }
    }

    public Optional<Entry> find(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /**
     * Entries whose title, agency or keywords mention {@code q},
     * case-insensitive, in the file's own (popularity) order. A blank query
     * is the library's "popular forms" row: the same order, the same cap.
     */
    public List<Entry> search(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(entry -> needle.isEmpty() || mentions(entry, needle))
                .limit(LIMIT)
                .toList();
    }

    private static boolean mentions(Entry entry, String needle) {
        return entry.title().toLowerCase(Locale.ROOT).contains(needle)
                || entry.agency().toLowerCase(Locale.ROOT).contains(needle)
                || entry.keywords().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(needle));
    }
}
