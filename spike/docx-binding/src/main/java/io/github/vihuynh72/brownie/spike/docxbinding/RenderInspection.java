package io.github.vihuynh72.brownie.spike.docxbinding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Independently inspects the PDFs {@code render/convert.sh} produces from the qualified DOCX
 * fixtures. This is deliberately a plain runnable, not a JUnit test: the PDFs only exist after
 * the Docker-based renderer has actually run, which {@code mvn test} has no part in, so a test
 * that expected them would either fail on every machine that hasn't run the renderer or need
 * skip logic that hides the one thing worth checking. Run it after {@code render/convert.sh}.
 *
 * <p>Checks what actually matters for the render fidelity gate, not just "a file exists": that
 * the multi-item document truly spans more than one page, that every accented name survives
 * PDF text extraction (proving the font and encoding round-tripped, not just that it looks right
 * on screen), that long text is not silently truncated, and that the empty-action-item case
 * renders its explanatory line instead of an empty region or an error.
 */
final class RenderInspection {

  private RenderInspection() {}

  public static void main(String[] args) throws IOException {
    Path renderDir = Path.of("target", "render-output");
    if (!Files.isDirectory(renderDir)) {
      System.err.println("No " + renderDir + " directory -- run render/convert.sh first.");
      System.exit(1);
      return;
    }

    List<Path> pdfs;
    try (var stream = Files.list(renderDir)) {
      pdfs = stream.filter(p -> p.toString().endsWith(".pdf")).sorted(Comparator.naturalOrder()).toList();
    }
    if (pdfs.isEmpty()) {
      System.err.println("No PDFs found in " + renderDir + " -- run render/convert.sh first.");
      System.exit(1);
      return;
    }

    List<String> manifestLines = new ArrayList<>();
    manifestLines.add(renderManifestHeader());

    int failures = 0;
    for (Path pdf : pdfs) {
      Result result = inspect(pdf);
      manifestLines.add(result.manifestLine());
      System.out.println(result.report());
      failures += result.failureCount();
    }

    Files.writeString(renderDir.resolve("render-manifest.txt"), String.join("\n", manifestLines) + "\n", StandardCharsets.UTF_8);
    System.out.println();
    System.out.println("Wrote " + renderDir.resolve("render-manifest.txt"));

    if (failures > 0) {
      System.err.println(failures + " check(s) failed.");
      System.exit(1);
    }
  }

  private static String renderManifestHeader() {
    return String.join(
        "\n",
        "# Render manifest",
        "renderer=LibreOffice 4:7.4.7-1+deb12u14 (Debian package version)",
        "base_image=debian:bookworm-slim@sha256:88200866dfff7ea7f5cbcb6ec7c8a701889efe6fe859fe64d6990e4b07ea4171",
        "font_package=fonts-liberation2 2.1.5-1",
        "document_font=" + ProfessionalStyle.FONT_FAMILY,
        "inspected_with=Apache PDFBox 3.0.8",
        "");
  }

  private static Result inspect(Path pdf) throws IOException {
    String name = pdf.getFileName().toString();
    byte[] bytes = Files.readAllBytes(pdf);
    int pageCount;
    String text;
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      pageCount = doc.getNumberOfPages();
      text = new PDFTextStripper().getText(doc);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load " + name, e);
    }
    // PDFBox's text stripper inserts a line break at every visual line wrap, so a phrase that
    // happens to wrap mid-sentence would otherwise fail a plain contains() check even though
    // nothing is actually missing. Collapse all whitespace before comparing content, not layout.
    String normalizedText = text.replaceAll("\\s+", " ");

    List<Check> checks = new ArrayList<>();
    checks.add(new Check("has at least one page", pageCount >= 1));

    if (name.contains("multi-page")) {
      checks.add(new Check("multi-page scenario actually spans more than one page (got " + pageCount + ")", pageCount > 1));
    }
    if (name.contains("short")) {
      checks.add(new Check("short scenario fits on one page (got " + pageCount + ")", pageCount == 1));
    }
    if (name.contains("accented-names")) {
      for (String expected : List.of("José Núñez", "Zoë Åström", "François Müller", "Renée Dubois")) {
        checks.add(new Check("extracted text contains \"" + expected + "\"", normalizedText.contains(expected)));
      }
    }
    if (name.contains("long")) {
      checks.add(
          new Check(
              "long decision text is not truncated",
              normalizedText.contains("including the newly added exhibition event in the neighboring district")));
      checks.add(
          new Check(
              "long action-item task text is not truncated",
              normalizedText.contains("including the updated budget breakdown and the club's competition schedule")));
    }
    if (name.contains("empty") || name.contains("no-items")) {
      checks.add(
          new Check(
              "empty action-item list shows the explanatory line",
              normalizedText.contains(RepeatingRegion.NO_ACTION_ITEMS_TEXT)));
    }

    return new Result(name, pageCount, text.length(), checks);
  }

  private record Check(String description, boolean passed) {}

  private record Result(String name, int pageCount, int textLength, List<Check> checks) {

    int failureCount() {
      return (int) checks.stream().filter(c -> !c.passed()).count();
    }

    String report() {
      StringBuilder sb = new StringBuilder();
      sb.append(name).append(" -- pages=").append(pageCount).append(", text-length=").append(textLength).append('\n');
      for (Check check : checks) {
        sb.append("  [").append(check.passed() ? "PASS" : "FAIL").append("] ").append(check.description()).append('\n');
      }
      return sb.toString();
    }

    String manifestLine() {
      return name + " pages=" + pageCount + " text_length=" + textLength
          + " checks_failed=" + failureCount() + "/" + checks.size();
    }
  }
}
