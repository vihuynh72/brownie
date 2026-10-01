package io.github.vihuynh72.brownie.core.artifact;

/**
 * Which application wrote an iWork document, read from the first few bytes
 * of its {@code Index/Document.iwa}. Pages, Numbers and Keynote files are
 * the same kind of archive with the same part names, and a Pages document
 * with a table carries the same table parts a spreadsheet does, so the
 * names alone cannot tell a Pages document from a Numbers one. The first
 * object in the document part can: its message type is the application's
 * own document archive, 10000 in Pages and 1 in both Numbers and Keynote
 * (checked against every template the three applications ship).
 *
 * <p>The part is a series of chunks, each a four-byte header (a zero, then
 * a three-byte little-endian length) and a Snappy-compressed block. The
 * decompressed block begins with a length-prefixed {@code ArchiveInfo}
 * message whose second field holds {@code MessageInfo}s, the first field of
 * which is the type. Only the start of the first block is ever decoded, into
 * a buffer of fixed size, and anything that does not read as expected is
 * simply not recognized.
 */
final class IworkDocumentHeader {

    /** Bytes of the part kept for this; the header is in the first few dozen. */
    static final int HEAD_BYTES = 1024;

    static final long PAGES_DOCUMENT = 10000;
    static final long UNRECOGNIZED = -1;

    private static final int DECODED_LIMIT = 256;

    private IworkDocumentHeader() {
    }

    /** The first object's message type, or {@link #UNRECOGNIZED}. */
    static long firstMessageType(byte[] head, int length) {
        if (length < 5 || head[0] != 0) {
            return UNRECOGNIZED;
        }
        int blockLength = (head[1] & 0xFF) | (head[2] & 0xFF) << 8 | (head[3] & 0xFF) << 16;
        byte[] decoded = new byte[DECODED_LIMIT];
        int decodedLength = decompressStart(head, 4, Math.min(length, 4 + blockLength), decoded);
        if (decodedLength <= 0) {
            return UNRECOGNIZED;
        }
        return typeOfFirstMessage(decoded, decodedLength);
    }

    /**
     * Decodes Snappy elements from {@code in[from..to)} until the output is
     * full or the input runs out, and returns how many bytes were decoded,
     * or -1 when the input is not Snappy. A copy that would run past the
     * buffer stops the decoding there, which is all that is ever needed.
     */
    private static int decompressStart(byte[] in, int from, int to, byte[] out) {
        Cursor cursor = new Cursor(in, from, to);
        if (cursor.varint() < 0) {
            return -1;
        }
        int written = 0;
        while (written < out.length && cursor.remaining() > 0) {
            int tag = cursor.next();
            int kind = tag & 0x3;
            if (kind == 0) {
                long literal = tag >>> 2;
                if (literal >= 60) {
                    int width = (int) literal - 59;
                    if (cursor.remaining() < width) {
                        return -1;
                    }
                    literal = 0;
                    for (int i = 0; i < width; i++) {
                        literal |= (long) cursor.next() << (8 * i);
                    }
                }
                long count = Math.min(literal + 1, out.length - written);
                int available = (int) Math.min(count, cursor.remaining());
                for (int i = 0; i < available; i++) {
                    out[written++] = (byte) cursor.next();
                }
                if (available < count) {
                    break;
                }
            } else {
                int copyLength;
                long offset;
                if (kind == 1) {
                    if (cursor.remaining() < 1) {
                        return -1;
                    }
                    copyLength = 4 + ((tag >>> 2) & 0x7);
                    offset = (long) (tag & 0xE0) << 3 | cursor.next();
                } else {
                    int width = kind == 2 ? 2 : 4;
                    if (cursor.remaining() < width) {
                        return -1;
                    }
                    copyLength = 1 + (tag >>> 2);
                    offset = 0;
                    for (int i = 0; i < width; i++) {
                        offset |= (long) cursor.next() << (8 * i);
                    }
                }
                if (offset <= 0 || offset > written) {
                    return -1;
                }
                for (int i = 0; i < copyLength && written < out.length; i++) {
                    out[written] = out[written - (int) offset];
                    written++;
                }
            }
        }
        return written;
    }

    private static long typeOfFirstMessage(byte[] decoded, int length) {
        Cursor archive = new Cursor(decoded, 0, length);
        long infoLength = archive.varint();
        if (infoLength <= 0) {
            return UNRECOGNIZED;
        }
        Cursor info = archive.sub(infoLength);
        while (info != null && info.remaining() > 0) {
            long key = info.varint();
            if (key < 0) {
                return UNRECOGNIZED;
            }
            int wireType = (int) (key & 0x7);
            if (wireType == 0) {
                if (info.varint() < 0) {
                    return UNRECOGNIZED;
                }
            } else if (wireType == 2) {
                Cursor field = info.sub(info.varint());
                if (field == null) {
                    return UNRECOGNIZED;
                }
                if (key >>> 3 == 2) {
                    return firstField(field);
                }
            } else {
                return UNRECOGNIZED;
            }
        }
        return UNRECOGNIZED;
    }

    /** The value of field 1 of a {@code MessageInfo}, which is its type. */
    private static long firstField(Cursor messageInfo) {
        while (messageInfo.remaining() > 0) {
            long key = messageInfo.varint();
            if (key < 0) {
                return UNRECOGNIZED;
            }
            int wireType = (int) (key & 0x7);
            if (wireType == 0) {
                long value = messageInfo.varint();
                if (key >>> 3 == 1) {
                    return value;
                }
            } else if (wireType == 2) {
                if (messageInfo.sub(messageInfo.varint()) == null) {
                    return UNRECOGNIZED;
                }
            } else {
                return UNRECOGNIZED;
            }
        }
        return UNRECOGNIZED;
    }

    private static final class Cursor {

        private final byte[] bytes;
        private int position;
        private final int end;

        private Cursor(byte[] bytes, int position, int end) {
            this.bytes = bytes;
            this.position = position;
            this.end = Math.min(end, bytes.length);
        }

        int remaining() {
            return end - position;
        }

        int next() {
            return bytes[position++] & 0xFF;
        }

        /** A base-128 varint of at most 64 bits, or -1 when it runs past the end or is longer. */
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64 && position < end; shift += 7) {
                int b = next();
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value < 0 ? -1 : value;
                }
            }
            return -1;
        }

        /** The next {@code length} bytes as their own cursor, skipped here; null when they are not all present. */
        Cursor sub(long length) {
            if (length < 0 || length > remaining()) {
                return null;
            }
            Cursor sub = new Cursor(bytes, position, position + (int) length);
            position += (int) length;
            return sub;
        }
    }
}
