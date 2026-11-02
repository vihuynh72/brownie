package io.github.vihuynh72.brownie.api.transcription;

import java.nio.charset.StandardCharsets;

/**
 * Reads one named part out of a multipart/form-data body held in memory.
 * Deliberately small: the application leaves servlet multipart off
 * everywhere, so this is the only multipart body that exists, and it is
 * already bounded to ten megabytes before it reaches this class.
 */
final class MultipartParser {

    private MultipartParser() {
    }

    /** The bytes of the part whose name is {@code name}, or null when there is none. */
    static byte[] partNamed(byte[] body, String boundary, String name) {
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        int position = 0;
        while (true) {
            int start = indexOf(body, marker, position);
            if (start < 0) return null;
            int headersEnd = indexOf(body, new byte[] {'\r', '\n', '\r', '\n'}, start + marker.length);
            if (headersEnd < 0) return null;
            int contentStart = headersEnd + 4;
            int next = indexOf(body, marker, contentStart);
            int contentEnd = next < 0 ? body.length : next;
            String headers = new String(body, start + marker.length, headersEnd - start - marker.length,
                    StandardCharsets.ISO_8859_1);
            if (headers.contains("name=\"" + name + "\"")) {
                // The two bytes before the next boundary marker are the CRLF ending this part's content.
                int end = contentEnd >= 2 ? contentEnd - 2 : contentEnd;
                byte[] part = new byte[Math.max(0, end - contentStart)];
                System.arraycopy(body, contentStart, part, 0, part.length);
                return part;
            }
            position = contentEnd;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
