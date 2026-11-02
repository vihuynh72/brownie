package io.github.vihuynh72.brownie.core.detail;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class DetailKeyTest {

    @Test
    void everyKeyIsUniqueAndWellFormed() {
        List<String> keys = java.util.Arrays.stream(DetailKey.values()).map(DetailKey::key).toList();
        assertEquals(keys.size(), keys.stream().distinct().count());
        for (String key : keys) {
            assertTrue(key.matches("^[a-z_]+\\.[a-z_0-9]+$"), () -> "malformed key " + key);
        }
    }

    @Test
    void theSensitiveSetIsExactlyTheDesignsList() {
        assertEquals(
                java.util.Set.of(
                        "sensitive.ssn",
                        "sensitive.passport_number",
                        "sensitive.alien_number",
                        "sensitive.drivers_license_number",
                        "sensitive.bank_account",
                        "sensitive.bank_routing",
                        "sensitive.insurance_member_id",
                        "sensitive.medical_conditions",
                        "sensitive.medications"),
                DetailKey.sensitiveKeys().stream().map(DetailKey::key).collect(Collectors.toSet()));
    }

    @Test
    void everyKeyFindsItselfBySlug() {
        for (DetailKey key : DetailKey.values()) {
            assertEquals(key, DetailKey.find(key.key()).orElseThrow());
        }
        assertTrue(DetailKey.find("not.a.key.yet.maybe").isEmpty());
    }
}
