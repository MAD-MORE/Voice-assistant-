package com.madmore.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private val parser = CommandParser()

    @Test fun englishCallIsParsed() {
        val command = parser.parse("Call Gyamera")
        assertEquals(CommandType.CALL, command.type)
        assertEquals("gyamera", parser.normalize(command.target!!))
    }

    @Test fun akanCallIsParsed() {
        val command = parser.parse("Frɛ me ba")
        assertEquals(CommandType.CALL, command.type)
        assertEquals("ba", parser.normalize(command.target!!))
    }

    @Test fun naturalCallSentenceIsParsed() {
        val command = parser.parse("Please call Padmore for me")
        assertEquals(CommandType.CALL, command.type)
        assertEquals("padmore", parser.normalize(command.target!!))
    }

    @Test fun stopIsRecognized() {
        assertEquals(CommandType.STOP, parser.parse("gyae").type)
        assertEquals(CommandType.STOP, parser.parse("stop listening").type)
    }

    @Test fun fuzzyMatchingHandlesPronunciationVariation() {
        val matcher = FuzzyMatcher()
        assertTrue(matcher.similarity("Gyamera", "Gyamera") > 0.99)
        assertTrue(matcher.similarity("Gyamera", "Gyemera") >= 0.75)
        assertTrue(matcher.similarity("Kofi", "Cofi") >= 0.75)
    }

    @Test fun tokenMatchingHandlesFirstNameAndFullName() {
        val matcher = FuzzyMatcher()
        assertTrue(matcher.tokenAwareSimilarity("Padmore", "Padmore Yeboah") >= 0.90)
        assertTrue(matcher.tokenAwareSimilarity("Gyamera", "Kofi Gyamera") >= 0.90)
    }

    @Test fun akanLettersNormalizeForSpeechRecognition() {
        assertEquals("fre me ba", parser.normalize("Frɛ me ba"))
        assertEquals("me yere", parser.normalize("Me Yɛre"))
        assertEquals("nkyere me contacts", parser.normalize("Nkyerɛ me contacts"))
    }
}
