package io.github.vihuynh72.brownie.core.connector;

/**
 * Names of things copied in from a connected account, as Brownie keeps them:
 * on one line, and cut to a length any page can show, between characters and
 * never through one.
 */
final class ImportedNames {

    private static final int MAX_FILENAME_CODE_POINTS = 200;
    /** Leaves room for ".txt" inside the 255 characters a file name is kept to. */
    private static final int MAX_FILENAME_BASE_CHARS = 251;

    private ImportedNames() {
    }

    /**
     * {@code name} as the name of a text file: on one line, a slash turned
     * into a dash so it is never read as a folder, cut, and ending in
     * {@code .txt}; {@code fallback} when nothing printable is left.
     */
    static String textFileName(String name, String fallback) {
        String oneLine = CalendarEventText.oneLine(name).replace('/', '-').replace('\\', '-');
        String base = oneLine.isEmpty() ? fallback : firstCodePoints(oneLine, MAX_FILENAME_CODE_POINTS, MAX_FILENAME_BASE_CHARS).strip();
        return base + ".txt";
    }

    /** At most {@code maxCodePoints} characters and {@code maxChars} UTF-16 units, cut between characters, never through one. */
    static String firstCodePoints(String text, int maxCodePoints, int maxChars) {
        int end = 0;
        int codePoints = 0;
        while (end < text.length() && codePoints < maxCodePoints) {
            int next = text.offsetByCodePoints(end, 1);
            if (next > maxChars) {
                break;
            }
            end = next;
            codePoints++;
        }
        return text.substring(0, end);
    }
}
