package io.github.luccoj.alertroid

import io.github.luccoj.alertroid.alert.NumberWords
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NumberWordsTest {
    @Test fun english() {
        val en = Locale.ENGLISH
        assertEquals("seven", NumberWords.spell(7, en))
        assertEquals("twenty", NumberWords.spell(20, en))
        assertEquals("forty-five", NumberWords.spell(45, en))
        assertEquals("one hundred twelve", NumberWords.spell(112, en))
    }

    @Test fun italian() {
        val it = Locale.ITALIAN
        assertEquals("diciassette", NumberWords.spell(17, it))
        assertEquals("ventuno", NumberWords.spell(21, it))
        assertEquals("ventitré", NumberWords.spell(23, it))
        assertEquals("trentotto", NumberWords.spell(38, it))
        assertEquals("novanta", NumberWords.spell(90, it))
        assertEquals("cento", NumberWords.spell(100, it))
        assertEquals("centotto", NumberWords.spell(108, it))
        assertEquals("centoventi", NumberWords.spell(120, it))
    }

    @Test fun otherLanguagesUseDigits() {
        assertEquals("15", NumberWords.spell(15, Locale.GERMAN))
        assertEquals("250", NumberWords.spell(250, Locale.ENGLISH))
    }
}
