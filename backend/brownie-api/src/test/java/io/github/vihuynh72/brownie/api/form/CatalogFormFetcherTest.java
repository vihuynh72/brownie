package io.github.vihuynh72.brownie.api.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fetcher's fences, against a stub server: only a host the allowlist
 * names, at most two redirects and never off the allowlist, the byte cap.
 * No real government site is touched here.
 */
class CatalogFormFetcherTest {

    private HttpServer server;
    private CatalogFormFetcher fetcher;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        fetcher = new HttpCatalogFormFetcher(Set.of("127.0.0.1", "localhost"), false);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static FormLibraryCatalog.Entry entry(String url) {
        return new FormLibraryCatalog.Entry(
                "test-form", "Test form", "Test Agency", "Tax", "A test.", url, List.of("test"));
    }

    @Test
    void anAllowlistedHostSendsItsBytes() throws Exception {
        byte[] pdf = "%PDF-fake".getBytes(StandardCharsets.UTF_8);
        server.createContext("/form.pdf", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, pdf.length);
            exchange.getResponseBody().write(pdf);
            exchange.close();
        });

        assertThat(fetcher.fetch(entry(url("/form.pdf")))).isEqualTo(pdf);
    }

    @Test
    void aHostOutsideTheAllowlistIsRefusedBeforeAnyRequest() {
        assertThatThrownBy(() -> fetcher.fetch(entry("http://elsewhere.example.gov/form.pdf")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void aRedirectToAnotherHostIsRefused() {
        server.createContext("/jump", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://elsewhere.example.gov/form.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> fetcher.fetch(entry(url("/jump"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void moreThanTwoRedirectsIsGivenUpOn() {
        server.createContext("/r1", exchange -> {
            exchange.getResponseHeaders().set("Location", url("/r2"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/r2", exchange -> {
            exchange.getResponseHeaders().set("Location", url("/r3"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/r3", exchange -> {
            exchange.getResponseHeaders().set("Location", url("/r4"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> fetcher.fetch(entry(url("/r1"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("redirecting");
    }

    @Test
    void aFormBiggerThanTheCapIsRefusedBeforeItStreams() {
        server.createContext("/huge.pdf", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 11; i++) {
                exchange.getResponseBody().write(chunk);
            }
            exchange.close();
        });

        assertThatThrownBy(() -> fetcher.fetch(entry(url("/huge.pdf"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cap");
    }

    @Test
    void anythingButTwoHundredIsAFailure() {
        server.createContext("/gone.pdf", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> fetcher.fetch(entry(url("/gone.pdf"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }
}
