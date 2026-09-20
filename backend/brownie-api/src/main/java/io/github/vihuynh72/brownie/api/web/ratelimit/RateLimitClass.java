package io.github.vihuynh72.brownie.api.web.ratelimit;

/**
 * What a request costs the host, which is what decides how many of them
 * one person may make in a minute. The classes are few on purpose: the
 * aim is that no single account can exhaust the machine, not to meter
 * ordinary use, and every default is far above what the web app does.
 */
public enum RateLimitClass {
    /** Starts paid model work. The usage ledger bounds the money; this bounds how fast one person can queue it. */
    MODEL,
    /** Starts an isolated render: validate, compile, export, a template's activation. The most expensive request there is. */
    RENDER,
    /** Sends or finalizes a file, which means storage, a virus scan and a parser. */
    UPLOAD,
    /** Any other change. */
    WRITE,
    /** Any read. The web app polls a running job about forty times a minute, so this is the generous one. */
    READ,
    /** Anything from someone who is not signed in, counted per address because there is no person to count by. */
    ANONYMOUS
}
