package app.yourneeds.foliocue

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRemovalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun removesOnlySelectedCopiesAndSupportsRetry() {
        val books = temp.newFolder("books")
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        for (name in listOf(id, other, "local")) {
            val dir = File(books, name)
            assertTrue(dir.mkdir())
            File(dir, "example.epub").writeText("fixture")
        }
        removeProfileBooks(books, id)
        removeProfileBooks(books, id)
        assertFalse(File(books, id).exists())
        assertTrue(File(books, "$other/example.epub").isFile)
        assertTrue(File(books, "local/example.epub").isFile)
    }

    @Test fun rejectsLocalShelfAndPaths() {
        for (id in listOf("", "local", "..", "../local")) {
            assertThrows(IllegalArgumentException::class.java) { removeProfileBooks(temp.root, id) }
        }
    }
}
