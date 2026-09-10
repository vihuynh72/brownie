package io.github.vihuynh72.brownie.core.artifact;

/**
 * What a {@link MalwareScanner} actually observed for one completed scan.
 * A scanner that could not complete a scan at all (unreachable, timed out,
 * gave a response nothing recognizes) throws instead of returning one of
 * these -- this type only exists for a scan that genuinely finished.
 */
public record ScanResult(boolean clean, String signatureName) {

    public static ScanResult ok() {
        return new ScanResult(true, null);
    }

    public static ScanResult infected(String signatureName) {
        return new ScanResult(false, signatureName);
    }
}
