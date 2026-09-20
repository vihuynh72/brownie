package io.github.vihuynh72.brownie.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Refuses a request body larger than any request to this API has reason to
 * be. Without it the limit on a body is whatever the server's memory is: a
 * body is read whole before a controller sees it, so one request could ask
 * the server to hold whatever it likes.
 *
 * <p>It applies to every body except the one route that receives a file,
 * which has its own, larger limit enforced where the bytes are stored.
 * What kind of body the request says it carries is not asked: a body is
 * read into the same objects whether it is labelled JSON or anything else
 * a converter on the class path understands, and a limit that went by the
 * label would be a limit on the honest.
 *
 * <p>A body that declares its length is refused before it is read; one
 * that does not is cut off as it is read, the moment it passes the limit.
 * It runs before sign-in and the session lookup, which is before anything
 * reads a body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final Pattern FILE_UPLOAD = Pattern.compile("^/api/v1/workspaces/[^/]+/uploads/[^/]+/content$");
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final long maxBytes;

    public RequestBodyLimitFilter(@Value("${brownie.web.max-body-bytes:1048576}") long maxBytes) {
        if (maxBytes < 1024) {
            throw new IllegalArgumentException("brownie.web.max-body-bytes must be at least 1024.");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "PUT".equals(request.getMethod()) && FILE_UPLOAD.matcher(PATHS.getPathWithinApplication(request)).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            refuse(request, response, maxBytes);
            return;
        }
        filterChain.doFilter(new BoundedRequest(request, maxBytes), response);
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response, long maxBytes) throws IOException {
        FilterProblemWriter.write(
                request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Content Too Large",
                "A request body may be at most " + maxBytes + " bytes.", "CONTENT_TOO_LARGE");
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        private BoundedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream body = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int value = body.read();
                    if (value >= 0) {
                        count(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] target, int offset, int length) throws IOException {
                    int count = body.read(target, offset, length);
                    if (count > 0) {
                        count(count);
                    }
                    return count;
                }

                private void count(int bytes) {
                    read += bytes;
                    if (read > maxBytes) {
                        throw new RequestBodyTooLargeException(maxBytes);
                    }
                }

                @Override
                public boolean isFinished() {
                    return body.isFinished();
                }

                @Override
                public boolean isReady() {
                    return body.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    body.setReadListener(listener);
                }
            };
        }
    }
}
