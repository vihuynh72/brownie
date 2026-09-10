package io.github.vihuynh72.brownie.core.artifact;

/**
 * Turns a client-supplied filename into pure display metadata: never a
 * filesystem or blob path (the blob key is always a separate, opaque,
 * server-generated value), never used for anything but showing the user
 * what they called their own file.
 */
public final class DisplayFilenames {

    private static final int MAX_LENGTH = 255;

    private DisplayFilenames() {
    }

    /** Null in, null out. A name that sanitizes down to nothing (only path separators, only control characters) also returns null rather than an empty, misleading label. */
    public static String sanitize(String rawFilename) {
        if (rawFilename == null) {
            return null;
        }
        String lastSegment = lastPathSegment(rawFilename);
        String withoutControlCharacters = stripControlCharacters(lastSegment);
        String trimmed = withoutControlCharacters.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }

    private static String lastPathSegment(String name) {
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return lastSlash < 0 ? name : name.substring(lastSlash + 1);
    }

    private static String stripControlCharacters(String name) {
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }
}
