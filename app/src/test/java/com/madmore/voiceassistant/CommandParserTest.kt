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

    @Test fun stopIsRecognized() {
        assertEquals(CommandType.STOP, parser.parse("gyae").type)
        assertEquals(CommandType.STOP, parser.parse("stop listening").type)
    }

    @Test fun fuzzyMatchingHandlesPronunciationVariation() {
        val matcher = FuzzyMatcher()
        assertTrue(matcher.similarity("Gyamera", "Gyamera") > 0.99)
        assertTrue(matcher.similarity("Gyamera", "Gyemera") >= 0.75)
    }
}
