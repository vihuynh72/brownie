package io.github.vihuynh72.brownie.core.connector;

/**
 * The only three things Brownie ever asks Google Drive: what one file is, a
 * Google Doc's text, and a plain-text file's bytes, each for one file the
 * person picked. There is deliberately nothing that lists, searches or
 * changes files: Brownie does not look through anyone's Drive, and it does
 * not write to it.
 *
 * <h2>What the caller already guarantees</h2>
 * <ul>
 * <li>{@code accessToken} is a fresh, non-blank Google access token carrying
 * only the permission to open files the person picked, valid for this one
 * request.</li>
 * <li>{@code fileId} is 1 to 256 letters, digits, {@code -} or {@code _}: a
 * file the person picked, or one being confirmed as picked. It is data, never
 * trusted as anything else.</li>
 * <li>{@code maxBytes} is between 1 and Brownie's upload limit.</li>
 * <li>{@link #readGoogleDocAsText} is called only for a file
 * {@link #describeFile} just reported as a Google Doc, and {@link
 * #readTextFile} only for one reported as {@code text/plain}; neither is
 * called for a file reported as trashed or as not downloadable.</li>
 * </ul>
 *
 * <h2>What each answer must be</h2>
 * <ul>
 * <li>{@link #describeFile} never reads the content. Its {@link DriveFile}
 * gives the id as Drive states it (the caller checks it equals the one asked
 * for), Drive's type string unchanged (a shortcut is reported as a shortcut
 * and never followed to its target), whether the file is in the trash
 * (directly or through its folder; that is not an error), whether this person
 * may download it now (false when Drive says they may not, or does not say),
 * and Drive's version number as text. Name, size, last change and the link
 * that opens the file are given when Drive states them and null otherwise.
 * A file in a shared drive, or one shared with the person, is described like
 * any other.</li>
 * <li>{@link #readGoogleDocAsText} returns the plain text Google's own export
 * of the Doc produces, and {@link #readTextFile} the file's stored bytes,
 * each exactly as Google sends them: never decoded, re-encoded, trimmed or
 * added to, and a leading byte order mark kept. Neither ever keeps or
 * returns more than {@code maxBytes}, or part of the content: more than
 * that is {@link ConnectorResourceTooLargeException}.</li>
 * </ul>
 *
 * <h2>Which exception means what</h2>
 * All are unchecked; no other kind may escape (no I/O, parsing or null
 * pointer exception from a missing field).
 * <ul>
 * <li>{@link ConnectorResourceUnavailableException} with {@code GONE}: Google
 * says the file does not exist, or that this person cannot open it (it does
 * not tell those apart); with {@code ACCESS_LOST}: Google says this app has
 * not been given access to this file; with {@code DOWNLOAD_RESTRICTED}:
 * Google refuses the content because downloading, copying or exporting it is
 * restricted for this person. Each is thrown for {@link
 * ConnectorAccess#DRIVE_FILES}.</li>
 * <li>{@link ConnectorResourceTooLargeException}: the content is larger than
 * {@code maxBytes}, or Google says the file is too large to export.</li>
 * <li>{@link ProviderTokenRejectedException}: Google refuses the access token
 * itself.</li>
 * <li>{@link ProviderUnavailableException}: no answer, a timeout or a
 * refused connection; Google failing on its side or limiting requests for
 * now; or an answer that cannot be understood (not JSON, or without an id, a
 * type or a version).</li>
 * <li>{@link ConnectorBlockedByOrganizationException} for {@link
 * ConnectorAccess#DRIVE_FILES}: the organization that manages the account
 * does not allow Drive apps.</li>
 * <li>{@link ProviderMisconfiguredException}: any other refusal, which points
 * at Brownie's own setup, such as the Drive API not being enabled for it.</li>
 * </ul>
 *
 * <h2>Limits</h2>
 * No retries, no paging and no background work: each call asks only about
 * the one file given, and only for what {@link DriveFile} needs. An
 * implementation keeps no state between calls, so it is safe to call from
 * many requests at once.
 *
 * <h2>What an implementation must never do</h2>
 * Create, change, copy, move, rename, trash, delete or share anything, or
 * change a permission (nothing is sent that is not a read); list, search or
 * browse files, folders, drives, revisions or changes; read anything but the
 * one file given (no comments, revisions, permissions or people); subscribe
 * to changes; use Google's option for downloading a file flagged as abusive;
 * follow a redirect, or call any host but the one it is configured with;
 * keep the token or the content anywhere; or put the token, the file's id,
 * its name, its content or Google's free-text message into a log line, an
 * exception message or an address.
 */
public interface DriveFileReader {

    DriveFile describeFile(String accessToken, String fileId);

    byte[] readGoogleDocAsText(String accessToken, String fileId, int maxBytes);

    byte[] readTextFile(String accessToken, String fileId, int maxBytes);
}
