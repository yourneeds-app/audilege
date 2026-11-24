package app.yourneeds.foliocue

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.*

class AppVolumeControlTest {
    @Test fun cardIsCenteredInTheWindowEvenWhenItsAnchorIsAnEmptyEffectOrDialogSlot() {
        val screen = IntSize(393, 800)
        val card = IntSize(260, 180)
        assertEquals(IntOffset(66, 310), AppVolumePosition.calculatePosition(IntRect.Zero, screen, LayoutDirection.Ltr, card))
        assertEquals(IntOffset(66, 310), AppVolumePosition.calculatePosition(IntRect(30, 200, 350, 500), screen, LayoutDirection.Rtl, card))
    }
    private class Output : AppVolumeBackend {
        var value = AppVolume("local:phone", "Phone", 7, 15)
        var writes = 0
        var fail = false
        override fun read(): AppVolume = value
        override fun adjust(target: AppVolume, command: VolumeCommand) {
            if (fail) error("Output unavailable")
            writes++
            value = value.copy(level = when (command) {
                VolumeCommand.UP -> (value.level + 1).coerceAtMost(value.maximum)
                VolumeCommand.DOWN -> (value.level - 1).coerceAtLeast(0)
                VolumeCommand.MUTE -> if (value.level == 0) 7 else 0
            })
        }
        override fun set(target: AppVolume, percent: Int) {
            if (fail) error("Output unavailable")
            writes++
            value = value.copy(level = percent * value.maximum / 100)
        }
    }

    @Test fun keyDownRepeatsAdjustButMatchingKeyUpNeverAdjustsTwice() {
        val backend = Output()
        val control = AppVolumeControl(backend).apply { resume() }
        assertTrue(control.dispatch(24, 0, 0, true))
        assertEquals(53, control.feedback!!.percent)
        assertTrue(control.dispatch(24, 0, 1, true))
        assertTrue(control.dispatch(24, 1, 0, true))
        assertEquals(2, backend.writes)
        assertEquals(60, control.feedback!!.percent)
        assertFalse(control.dispatch(25, 1, 0, true))
    }

    @Test fun backgroundAndUnfocusedWindowsNeverOwnKeysOrFeedback() {
        val backend = Output()
        val control = AppVolumeControl(backend)
        assertFalse(control.dispatch(24, 0, 0, true))
        control.resume()
        assertFalse(control.dispatch(24, 0, 0, false))
        assertTrue(control.dispatch(25, 0, 0, true))
        control.pause()
        assertNull(control.feedback)
        assertFalse(control.dispatch(25, 1, 0, true))
        assertFalse(control.dispatch(25, 0, 1, true))
        assertEquals(1, backend.writes)
    }

    @Test fun muteDoesNotToggleOnAutoRepeatAndUnrelatedKeysStayUntouched() {
        val backend = Output()
        val control = AppVolumeControl(backend).apply { resume() }
        assertFalse(control.dispatch(126, 0, 0, true)) // media play
        assertFalse(control.dispatch(92, 0, 0, true)) // page up
        assertTrue(control.dispatch(164, 0, 0, true))
        assertEquals(0, backend.value.level)
        assertTrue(control.dispatch(164, 0, 1, true))
        assertEquals(1, backend.writes)
        control.dispatch(164, 1, 0, true)
        control.dispatch(164, 0, 0, true)
        assertEquals(7, backend.value.level)
    }

    @Test fun changedOutputDismissesOldFeedbackAndStaleSliderCannotChangeNewSpeaker() {
        val backend = Output()
        val control = AppVolumeControl(backend).apply { resume() }
        control.dispatch(24, 0, 0, true)
        backend.value = AppVolume("airplay:new", "Living room", 25, 100)
        control.refresh()
        assertNull(control.feedback)
        assertFalse(control.set(90, "local:phone"))
        assertEquals(25, backend.value.level)
        assertTrue(control.set(30, "airplay:new"))
        assertEquals("Living room", control.feedback!!.name)
        assertEquals(30, control.feedback!!.percent)
    }

    @Test fun failedAdjustmentFallsBackAndFixedOutputsAreReadOnly() {
        val backend = Output()
        val control = AppVolumeControl(backend).apply { resume() }
        backend.fail = true
        assertFalse(control.dispatch(24, 0, 0, true))
        assertNull(control.feedback)
        backend.value = backend.value.copy(fixed = true)
        assertTrue(control.dispatch(24, 0, 0, true))
        assertTrue(control.feedback!!.fixed)
        assertEquals(0, backend.writes)
        assertEquals(0, AppVolume("fixed", "Fixed", 10, 0).percent)
    }

    @Test fun successfulWriteWithFailedReadbackStillConsumesTheKey() {
        var wrote = false
        val backend = object : AppVolumeBackend {
            override fun read(): AppVolume {
                if (wrote) error("Temporarily unavailable")
                return AppVolume("phone", "Phone", 5, 15)
            }
            override fun adjust(target: AppVolume, command: VolumeCommand) { wrote = true }
            override fun set(target: AppVolume, percent: Int) { wrote = true }
        }
        val control = AppVolumeControl(backend).apply { resume() }
        assertTrue(control.dispatch(24, 0, 0, true))
        assertTrue(control.dispatch(24, 1, 0, true))
        assertNull(control.feedback)
    }
}
