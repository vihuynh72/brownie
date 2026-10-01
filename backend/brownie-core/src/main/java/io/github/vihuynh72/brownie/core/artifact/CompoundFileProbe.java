package io.github.vihuynh72.brownie.core.artifact;

import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a Microsoft compound file (the container of Word 97-2003, Excel
 * and PowerPoint binary files, and of every password-encrypted Office
 * file) far enough to say what it holds. Reading that container needs a
 * library this module does not have, so it is a port: the document package
 * implements it, and the inspector calls it only for content that starts
 * with the compound file signature.
 */
public interface CompoundFileProbe {

    /** No reader plugged in: every compound file is refused as unrecognized, as it always was before one existed. */
    CompoundFileProbe NONE = content -> {
        throw new UnsupportedArtifactTypeException(
                "Content does not match any supported media type or package signature.");
    };

    /**
     * Reads {@code content}, a whole compound file already bounded by the
     * upload limit, to its end or as far as it needs. Returns which Word
     * binary format it is when it is an ordinary Word document ({@link
     * ConvertibleFormat#WORD_97} or {@link ConvertibleFormat#WORD_95});
     * throws {@link UnsupportedArtifactTypeException} with the reason for
     * anything else: an encrypted or rights-protected file, a spreadsheet, a
     * presentation, one that is not recognizable, or one that cannot be read.
     */
    ConvertibleFormat probe(InputStream content) throws IOException;
}
