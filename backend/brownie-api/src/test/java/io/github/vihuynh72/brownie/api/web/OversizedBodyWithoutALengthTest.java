package io.github.vihuynh72.brownie.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A body that does not say how long it is can only be refused while it is being read, which is inside whatever is
 * turning it into an object; that reader reports every failure as a body it could not read. This proves the answer
 * is still the one for a body that is too large, through the real filter, a real JSON reader and the real handler.
 */
class OversizedBodyWithoutALengthTest {

    @RestController
    static class Echo {

        @PostMapping("/echo")
        Map<String, String> echo(@RequestBody Map<String, String> body) {
            return body;
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Echo())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestBodyLimitFilter(2048))
            .build();

    @Test
    void aBodyCutOffWhileItIsBeingReadIsAnsweredAsTooLargeNotAsMalformed() throws Exception {
        String oversized = "{\"title\":\"" + "x".repeat(8192) + "\"}";

        mockMvc.perform(post("/echo").contentType("application/json").content(oversized).with(OversizedBodyWithoutALengthTest::withoutALength))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LARGE"));
    }

    @Test
    void aBodyThatReallyIsMalformedIsStillAnsweredAsThat() throws Exception {
        mockMvc.perform(post("/echo").contentType("application/json").content("{\"title\":").with(OversizedBodyWithoutALengthTest::withoutALength))
                .andExpect(status().isBadRequest());
    }

    /** The same request, as a client that streams its body sends it: with no length declared. */
    private static MockHttpServletRequest withoutALength(MockHttpServletRequest original) {
        MockHttpServletRequest unsized = new MockHttpServletRequest(original.getServletContext(), original.getMethod(), original.getRequestURI()) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        unsized.setContentType(original.getContentType());
        unsized.setContent(original.getContentAsByteArray());
        return unsized;
    }
}
