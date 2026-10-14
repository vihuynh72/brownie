package io.github.vihuynh72.brownie.api.mcp;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key is validated where it is read, at start-up: on without a
 * usable key is refused with a message that says what to set.
 */
class McpTokenKeyTest {

    @Test
    void aMissingKeyIsRefusedLoudly() {
        assertThatThrownBy(() -> McpTokenKey.fromBase64(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROWNIE_MCP_TOKEN_KEY");
        assertThatThrownBy(() -> McpTokenKey.fromBase64("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROWNIE_MCP_TOKEN_KEY");
    }

    @Test
    void aMalformedOrShortKeyIsRefusedLoudly() {
        assertThatThrownBy(() -> McpTokenKey.fromBase64("not base64 at all !!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
        assertThatThrownBy(() -> McpTokenKey.fromBase64(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void thirtyTwoBytesIsAccepted() {
        McpTokenKey key = McpTokenKey.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(key.key()).hasSize(32);
    }
}
