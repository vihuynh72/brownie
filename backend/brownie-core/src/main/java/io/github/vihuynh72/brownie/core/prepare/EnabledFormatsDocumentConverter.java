package io.github.vihuynh72.brownie.core.prepare;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A switch per format, so that one format's parser can be taken out of
 * reach (a flaw published in it, say) without refusing the others. A format
 * that is switched off is refused here, before a turn at the container is
 * taken or a byte of the file is staged: the point of switching a format off
 * is that its parser never sees another file.
 */
public final class EnabledFormatsDocumentConverter implements DocumentConverter {

    private final DocumentConverter delegate;
    private final Set<ConvertibleFormat> enabled;

    public EnabledFormatsDocumentConverter(DocumentConverter delegate, Set<ConvertibleFormat> enabled) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(enabled, "enabled");
        this.enabled = enabled.isEmpty() ? EnumSet.noneOf(ConvertibleFormat.class) : EnumSet.copyOf(enabled);
    }

    @Override
    public ConvertedDocument convertToDocx(byte[] source, ConvertibleFormat format) {
        Objects.requireNonNull(format, "format");
        if (!enabled.contains(format)) {
            throw new ConversionFormatDisabledException(format);
        }
        return delegate.convertToDocx(source, format);
    }

    /**
     * Reads the setting that lists the formats left on: the names of
     * {@link ConvertibleFormat}, separated by commas, in any case. Empty
     * means every format is off. A name that is not a format is refused
     * rather than skipped, because a misspelt name would otherwise quietly
     * switch that format off.
     */
    public static Set<ConvertibleFormat> parseEnabledFormats(String setting) {
        Set<ConvertibleFormat> formats = EnumSet.noneOf(ConvertibleFormat.class);
        if (setting == null) {
            return formats;
        }
        for (String part : setting.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                formats.add(ConvertibleFormat.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("\"" + name + "\" is not a format that can be converted; the formats are "
                        + EnumSet.allOf(ConvertibleFormat.class) + ".", e);
            }
        }
        return formats;
    }
}
