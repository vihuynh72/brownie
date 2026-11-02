package io.github.vihuynh72.brownie.core.prepare;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreparationNoticeTest {

    /**
     * A code a preparation can produce but the stored list does not know makes
     * every template carrying it fail to save, so every code constant is in it.
     */
    @Test
    void everyCodeConstantIsOneATemplateMayStore() throws IllegalAccessException {
        Set<String> constants = Arrays.stream(PreparationNotice.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
                .map(PreparationNoticeTest::valueOf)
                .collect(Collectors.toSet());

        assertEquals(constants, PreparationNotice.CODES);
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
