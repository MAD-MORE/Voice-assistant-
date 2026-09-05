package com.madmore.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {
    private val detector = WakeWordDetector()

    @Test fun exactWakeWordWins() {
        val match = detector.detect(listOf("Hello"))
        assertTrue(match.matched)
        assertEquals(1.0, match.confidence, 0.0001)
    }

    @Test fun commonSpeechRecognitionVariantsAreAccepted() {
        assertTrue(detector.detect(listOf("Helo")).matched)
        assertTrue(detector.detect(listOf("Helloooo")).matched)
        assertTrue(detector.detect(listOf("hallo")).matched)
    }

    @Test fun unrelatedSpeechDoesNotTrigger() {
        assertFalse(detector.detect(listOf("call my father")).matched)
        assertFalse(detector.detect(listOf("good morning")).matched)
    }

    @Test fun wakeAndCommandCanBeSeparated() {
        val match = detector.detect(listOf("Hello call Gyamera"))
        assertTrue(match.matched)
        assertEquals("call gyamera", detector.removeWakeWord(match.text))
    }
}
