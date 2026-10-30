package cn.sharetime.app

import org.junit.Assert.*
import org.junit.Test

class PairPhraseTest {
    @Test fun recognizesOnlyExplicitPhrases() {
        assertEquals("01234567", PairPhrase.parse(PairPhrase.create("01234567")))
        assertNull(PairPhrase.parse("01234567"))
        assertNull(PairPhrase.parse("SHARETIME-012345678"))
        assertNull(PairPhrase.parse("SHARETIME-01234567evil"))
        assertNull(PairPhrase.parse("bank account 12345678"))
    }
}
