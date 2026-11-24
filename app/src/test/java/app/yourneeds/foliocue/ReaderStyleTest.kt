package app.yourneeds.foliocue

import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderStyleTest {
    @Test fun textStaysReadableWithEveryHighlightAndPaper() {
        for (paper in Paper.entries) for (hue in 0..2) for (vivid in listOf(false, true)) {
            val style = ReaderStyle(paper, false, Typeface.Original, 100, 20, 1.5f, false, false, hue, vivid)
            assertTrue("Filled mark $paper /$hue /$vivid", contrast(style.highlightInk, style.highlight) >= 4.5)
            assertTrue("Underline $paper /$hue", contrast(style.highlightInk, style.paper.bg) >= 4.5)
        }
    }

    private fun contrast(a: Long, b: Long): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun luminance(color: Long): Double {
        fun channel(shift: Int): Double {
            val component = ((color shr shift) and 255) / 255.0
            return if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
