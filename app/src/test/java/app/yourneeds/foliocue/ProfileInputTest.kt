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

    @Test fun showsCleartextOptInOnlyForLocalAddressesThatCanUseIt() {
        assertTrue(offersLocalHttp("192.168.1.20:8011"))
        assertTrue(offersLocalHttp("http://shelf.local:8011"))
        assertFalse(offersLocalHttp("https://shelf.local:8011"))
        assertFalse(offersLocalHttp("books.example"))
        assertFalse(offersLocalHttp("http://10.0.0.1.elsewhere"))
    }
}
