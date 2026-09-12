package io.github.vihuynh72.brownie.core.rule;

/** A closed set of named date presentations -- not an arbitrary format-pattern string, consistent with adding constrained operators deliberately rather than accepting arbitrary input from ordinary users. */
public enum DateFormatStyle {
    /** For example "September 10, 2026". */
    LONG,
    /** For example "9/10/2026". */
    SHORT,
    /** For example "2026-09-10". */
    ISO
}
