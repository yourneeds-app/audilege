package app.yourneeds.foliocue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInputTest {
    @Test fun acceptsPastedAddressesWithOrWithoutAScheme() {
        assertEquals("https://books.example:8443", profileServerAddress(" books.example:8443/ ", false))
        assertEquals("http://192.168.178.150:8011", profileServerAddress("192.168.178.150:8011", true))
        assertEquals("https://books.example/api/v1/opds",
            profileServerAddress("https://books.example/api/v1/opds/", true))
        assertEquals("", profileServerAddress("   ", true))
    }

    @Test fun acceptsOpdsNamesAndRejectsBasicAuthSeparators() {
        assertTrue(validProfileUsername("school-reader"))
        assertTrue(validProfileUsername("reader@example.test"))
        assertFalse(validProfileUsername("reader:password"))
        assertFalse(validProfileUsername("reader\nname"))
        assertFalse(validProfileUsername(" "))
    }
}