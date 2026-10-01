package io.github.vihuynh72.brownie.core.prepare;

/**
 * Converts a word-processing file Brownie cannot fill directly into a Word
 * document it can. A real implementation must run the conversion isolated
 * from the trusted process calling it: this is the first place a stranger's
 * .doc, .rtf, .odt or .pages file is parsed at all, and the parsers that
 * read those formats are large and old. This interface carries no isolation
 * mechanism of its own; the module that wires it up supplies one.
 *
 * <p>Refusals: {@link DocumentConversionException} when the file itself
 * could not be converted (it did not open, it broke the converter, or it
 * took too long); {@link ConverterUnavailableException} when the converter
 * could not be run at all, which says nothing about the file;
 * {@link ConversionFormatDisabledException} when this deployment has turned
 * the format off; and {@code RenderCapacityExceededException} when every
 * slot the converter shares with rendering stayed busy.
 */
public interface DocumentConverter {

    ConvertedDocument convertToDocx(byte[] source, ConvertibleFormat format);
}
