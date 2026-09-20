package io.github.vihuynh72.brownie.api.security.clamav;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Answers whether the virus scanner is there. Every upload is scanned before
 * it can be used, so a scanner that is away means nobody can add anything --
 * a failure worth seeing in a probe rather than only in other people's failed
 * uploads.
 *
 * <p>It speaks the same protocol the scanner itself does, which for this
 * question is one word: send {@code zPING}, expect {@code PONG}. A scanner
 * still loading its signatures accepts the connection but does not answer,
 * which the read timeout turns into "not ready" -- correct, because it cannot
 * scan anything yet either.
 */
@Component
class ClamAvHealthIndicator implements HealthIndicator {

    private static final byte[] PING = "zPING\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 2_000;

    private final String host;
    private final int port;

    ClamAvHealthIndicator(
            @Value("${brownie.security.clamav.host}") String host,
            @Value("${brownie.security.clamav.port}") int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public Health health() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(PING);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] reply = new byte[16];
            int read = in.read(reply);
            String answer = read <= 0 ? "" : new String(reply, 0, read, StandardCharsets.US_ASCII).trim();
            // The reply is NUL-terminated in this mode, so it is the leading
            // word that is compared, not the whole buffer.
            if (answer.startsWith("PONG")) {
                return Health.up().build();
            }
            return Health.down().withDetail("reason", "unexpected reply").build();
        } catch (IOException e) {
            return Health.down().withDetail("reason", e.getClass().getSimpleName()).build();
        }
    }
}
