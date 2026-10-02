package io.github.vihuynh72.brownie.core.prepare;

/**
 * Makes the clean working copy of a person's Word file that Brownie fills,
 * leaving the file they uploaded as it was. Their file is accepted as it
 * is; what would make a filled copy wrong, leak, or reach outside the file
 * is settled in the copy instead: tracked changes are accepted, comments
 * are left out, macros, signatures and links to local files are removed,
 * fields that fetch something are frozen to the text they show, and
 * embedded files are turned into their pictures. What does not affect
 * filling (floating shapes, tables inside tables, fields Word works out by
 * itself, embedded spreadsheets and charts) is kept as it is. Each change
 * is reported as a {@link PreparationNotice} so the person can be told in
 * one line what happened.
 *
 * <p>Throws {@link WorkingCopyPreparationException} when the bytes cannot
 * be read as a Word document, or when the copy it made still holds
 * something it should have taken out.
 */
public interface WorkingCopyPreparer {

    PreparedCopy prepare(byte[] docx, PreparationMode mode);
}
