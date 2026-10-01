package io.github.vihuynh72.brownie.core.prepare;

import java.util.Objects;

/**
 * This deployment has switched converting this format off, so the file was
 * never handed to the converter. Unlike {@link ConverterUnavailableException}
 * this does not pass by itself: the same file is refused the same way until
 * the setting changes.
 */
public class ConversionFormatDisabledException extends RuntimeException {

    private final ConvertibleFormat format;

    public ConversionFormatDisabledException(ConvertibleFormat format) {
        super("Converting " + Objects.requireNonNull(format, "format") + " files is switched off on this server.");
        this.format = format;
    }

    public ConvertibleFormat format() {
        return format;
    }
}
