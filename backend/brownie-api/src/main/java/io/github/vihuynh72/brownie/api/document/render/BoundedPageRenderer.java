package io.github.vihuynh72.brownie.api.document.render;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Draws pages of a PDF that may have come straight from a person's upload,
 * holding the drawing to limits the library does not set itself:
 *
 * <ul>
 *   <li>a deadline, checked before every drawing operation, past which the
 *   page is abandoned ({@link DeadlinePassed});</li>
 *   <li>a limit on the pixels one picture declares, since the library sizes
 *   a picture's memory from that declaration;</li>
 *   <li>a limit on how far an inline picture expands, since the library
 *   expands those whole, into memory, just to read them.</li>
 * </ul>
 *
 * <p>A picture past a limit, or in a format the library has no decoder
 * for here (JBIG2 and JPEG 2000, which scans commonly use), is not drawn,
 * and {@link #pictureSkipped()} says so. Both drawings being compared skip
 * the same pictures, so the comparison stays fair for everything else on
 * the page.
 */
final class BoundedPageRenderer extends PDFRenderer {

    static final Set<String> UNDECODABLE_PICTURE_CODECS = Set.of("JBIG2Decode", "JPXDecode");
    private static final long MAX_INLINE_PICTURE_BYTES = 16L * 1024 * 1024;

    /** Thrown from inside the library's drawing, which carries on past a checked failure; unchecked so that it does not. */
    static final class DeadlinePassed extends RuntimeException {

        DeadlinePassed() {
            super("Drawing the page ran past the time allowed.");
        }
    }

    private final long deadlineNanos;
    private final long maxPixelsPerPicture;
    private boolean pictureSkipped;

    BoundedPageRenderer(PDDocument document, long deadlineNanos, long maxPixelsPerPicture) {
        super(document);
        this.deadlineNanos = deadlineNanos;
        this.maxPixelsPerPicture = maxPixelsPerPicture;
        setSubsamplingAllowed(true);
    }

    boolean pictureSkipped() {
        return pictureSkipped;
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        return new BoundedPageDrawer(parameters);
    }

    private final class BoundedPageDrawer extends PageDrawer {

        BoundedPageDrawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (System.nanoTime() - deadlineNanos > 0) {
                throw new DeadlinePassed();
            }
            String name = operator.getName();
            if ("Do".equals(name) && skipsNamedPicture(operands)) {
                pictureSkipped = true;
                return;
            }
            if ("BI".equals(name) && skipsInlinePicture(operator)) {
                pictureSkipped = true;
                return;
            }
            super.processOperator(operator, operands);
        }

        private boolean skipsNamedPicture(List<COSBase> operands) {
            if (operands.isEmpty() || !(operands.get(0) instanceof COSName pictureName)) {
                return false;
            }
            PDResources resources = getResources();
            COSDictionary xobjects = resources == null ? null : resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
            if (!(xobjects != null && xobjects.getDictionaryObject(pictureName) instanceof COSStream picture)
                    || !COSName.IMAGE.equals(picture.getCOSName(COSName.SUBTYPE))) {
                return false;
            }
            return undecodable(picture.getFilters()) || tooManyPixels(picture, COSName.WIDTH, COSName.HEIGHT);
        }

        private boolean skipsInlinePicture(Operator operator) {
            COSDictionary parameters = operator.getImageParameters();
            byte[] data = operator.getImageData();
            if (parameters == null || data == null) {
                return false;
            }
            COSBase filters = parameters.getDictionaryObject(COSName.F, COSName.FILTER);
            if (undecodable(filters) || tooManyPixels(parameters, COSName.W, COSName.H)) {
                return true;
            }
            long width = parameters.getLong(COSName.W, parameters.getLong(COSName.WIDTH, 0));
            long height = parameters.getLong(COSName.H, parameters.getLong(COSName.HEIGHT, 0));
            // Four eight-bit channels is the most a picture's pixels can take, with a little room for the codec's framing; and
            // a picture written inside a page's content is meant to be small, so a few megabytes is already far past any.
            long mostItCanHold = Math.min(width * height * 4 + 64 * 1024, MAX_INLINE_PICTURE_BYTES);
            return !expandsWithin(filters, parameters, data, mostItCanHold);
        }

        private boolean tooManyPixels(COSDictionary picture, COSName widthKey, COSName heightKey) {
            long width = Math.max(0, picture.getLong(widthKey, picture.getLong(COSName.WIDTH, 0)));
            long height = Math.max(0, picture.getLong(heightKey, picture.getLong(COSName.HEIGHT, 0)));
            return width * height > maxPixelsPerPicture;
        }
    }

    private static boolean undecodable(COSBase declared) {
        for (String name : filterNames(declared)) {
            if (UNDECODABLE_PICTURE_CODECS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> filterNames(COSBase declared) {
        List<String> names = new ArrayList<>();
        COSBase value = declared instanceof COSObject reference ? reference.getObject() : declared;
        if (value instanceof COSName name) {
            names.add(name.getName());
        } else if (value instanceof COSArray array) {
            for (COSBase element : array) {
                COSBase item = element instanceof COSObject reference ? reference.getObject() : element;
                if (item instanceof COSName name) {
                    names.add(name.getName());
                }
            }
        }
        return names;
    }

    /** Runs an inline picture's codecs into an output that keeps only what the next codec needs and stops past {@code limit}. */
    private static boolean expandsWithin(COSBase declared, COSDictionary parameters, byte[] data, long limit) {
        byte[] current = data;
        List<String> names = filterNames(declared);
        try {
            for (int index = 0; index < names.size(); index++) {
                Limited output = new Limited(limit);
                try (InputStream input = new ByteArrayInputStream(current)) {
                    FilterFactory.INSTANCE.getFilter(names.get(index)).decode(input, output, parameters, index);
                }
                current = output.bytes.toByteArray();
            }
            return true;
        } catch (Limited.Exceeded e) {
            return false;
        } catch (IOException | RuntimeException e) {
            // A picture that cannot be expanded at all is the library's to report when it tries; it costs nothing here.
            return true;
        }
    }

    private static final class Limited extends OutputStream {

        static final class Exceeded extends RuntimeException {
        }

        private final long limit;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        Limited(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) {
            if (bytes.size() + 1L > limit) {
                throw new Exceeded();
            }
            bytes.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            if (bytes.size() + (long) length > limit) {
                throw new Exceeded();
            }
            bytes.write(buffer, offset, length);
        }
    }
}
