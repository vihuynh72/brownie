package io.github.vihuynh72.brownie.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A message's images are pages the model can see; only the user sends them. */
class ModelMessageTest {

    @Test
    void aUserMessageCarriesPageImages() {
        ModelImage page = new ModelImage(new byte[]{1, 2, 3}, ModelImage.MEDIA_TYPE_PNG);
        ModelMessage message = new ModelMessage(ModelMessageRole.USER, "fill this", List.of(page));
        assertEquals(1, message.images().size());
        assertEquals(ModelImage.MEDIA_TYPE_PNG, message.images().get(0).mediaType());
    }

    @Test
    void aSystemOrAssistantMessageCannotCarryImages() {
        ModelImage page = new ModelImage(new byte[]{1}, ModelImage.MEDIA_TYPE_PNG);
        assertThrows(IllegalArgumentException.class,
                () -> new ModelMessage(ModelMessageRole.SYSTEM, "policy", List.of(page)));
    }

    @Test
    void anImageIsPngOrJpegAndFitsInFourMegabytes() {
        assertThrows(IllegalArgumentException.class, () -> new ModelImage(new byte[]{1}, "image/gif"));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelImage(new byte[ModelImage.MAX_BYTES + 1], ModelImage.MEDIA_TYPE_PNG));
    }

    @Test
    void aMessageCarriesAtMostTenImages() {
        List<ModelImage> pages = java.util.stream.IntStream.range(0, ModelMessage.MAX_IMAGES + 1)
                .mapToObj(i -> new ModelImage(new byte[]{(byte) i}, ModelImage.MEDIA_TYPE_PNG)).toList();
        assertThrows(IllegalArgumentException.class,
                () -> new ModelMessage(ModelMessageRole.USER, "fill", pages));
    }

    @Test
    void aTextOnlyMessageKeepsWorking() {
        ModelMessage message = new ModelMessage(ModelMessageRole.USER, "text");
        assertTrue(message.images().isEmpty());
    }
}
