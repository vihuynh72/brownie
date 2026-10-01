package io.github.vihuynh72.brownie.core.prepare;

import java.util.Objects;

/**
 * One line of a document's outline as the naming step reads it: a
 * paragraph, a heading or a table row. {@code key} is Brownie's own short
 * name for the line; {@code text} is the document's text with a marker
 * written as {@code [[c12]]} wherever candidate {@code c12} sits.
 *
 * <p>Two key shapes mean something to the naming step. A key that is
 * {@code H} followed only by digits ({@code H}, {@code H2}) is a heading,
 * which is always shown however far it is from a place to fill. A key that
 * starts with {@code T} and a table number ({@code T1R3}) is a row of that
 * table, and a table's rows are kept together with the line before them.
 */
public record OutlineLine(String key, String text) {

    public OutlineLine {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(text, "text");
    }
}
