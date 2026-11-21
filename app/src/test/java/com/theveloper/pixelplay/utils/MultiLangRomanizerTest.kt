package com.theveloper.pixelplay.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MultiLangRomanizerTest {

    private val romanizer = MultiLangRomanizer

    @Test
    fun `each script is recognised by its own letters`() {
        assertThat(romanizer.isKorean("안녕")).isTrue()
        assertThat(romanizer.isHindi("नमस्ते")).isTrue()
        assertThat(romanizer.isPunjabi("ਪਿਆਰ")).isTrue()
        assertThat(romanizer.isCyrillic("Привет")).isTrue()
        assertThat(romanizer.isChinese("你好")).isTrue()
        assertThat(romanizer.isJapanese("こんにちは")).isTrue()
        assertThat(romanizer.isJapanese("カタカナ")).isTrue()
    }

    @Test
    fun `latin text is none of them`() {
        val text = "hello world"

        assertThat(romanizer.isKorean(text)).isFalse()
        assertThat(romanizer.isHindi(text)).isFalse()
        assertThat(romanizer.isPunjabi(text)).isFalse()
        assertThat(romanizer.isCyrillic(text)).isFalse()
        assertThat(romanizer.isChinese(text)).isFalse()
        assertThat(romanizer.isJapanese(text)).isFalse()
        assertThat(romanizer.isScriptThatNeedsRomanization(text)).isFalse()
        assertThat(romanizer.isScriptThatNeedsRomanization("")).isFalse()
    }

    @Test
    fun `a line of kanji only is Japanese only when the whole song has kana`() {
        assertThat(romanizer.isJapanese("愛")).isFalse()
        assertThat(romanizer.isJapanese("愛", entireLyricsHasKana = true)).isTrue()
        assertThat(romanizer.isJapanese("hello", entireLyricsHasKana = true)).isFalse()
    }

    @Test
    fun `every script that is romanised is flagged as needing it`() {
        listOf("こんにちは", "カタカナ", "你好", "안녕", "नमस्ते", "ਪਿਆਰ", "Привет").forEach {
            assertThat(romanizer.isScriptThatNeedsRomanization(it)).isTrue()
        }
        assertThat(romanizer.isScriptThatNeedsRomanization("hello 안녕")).isTrue()
    }

    @Test
    fun `Korean is written the way it is said`() {
        assertThat(romanizer.romanizeKorean("안녕하세요")).isEqualTo("annyeonghaseyo")
        assertThat(romanizer.romanizeKorean("사랑해")).isEqualTo("saranghae")
        assertThat(romanizer.romanizeKorean("한국어")).isEqualTo("hangugeo")
    }

    @Test
    fun `Korean keeps what is not Hangul where it was`() {
        assertThat(romanizer.romanizeKorean("a 한 b")).isEqualTo("a han b")
        assertThat(romanizer.romanizeKorean("")).isEmpty()
    }

    @Test
    fun `Russian is romanised word by word`() {
        assertThat(romanizer.romanizeCyrillic("Привет")).isEqualTo("Privet")
        assertThat(romanizer.romanizeCyrillic("Спасибо")).isEqualTo("Spasibo")
        assertThat(romanizer.romanizeCyrillic("Москва")).isEqualTo("Moskva")
        assertThat(romanizer.romanizeCyrillic("Я тебя люблю")).isEqualTo("Ya tebya lyublyu")
    }

    @Test
    fun `Ukrainian text takes its own path and comes out in Latin letters`() {
        val result = romanizer.romanizeCyrillic("Привіт")

        assertThat(result).isNotNull()
        assertThat(result!!.none { it in 'Ѐ'..'ӿ' }).isTrue()
    }

    @Test
    fun `text without Cyrillic, empty text and a lone e are not romanised`() {
        assertThat(romanizer.romanizeCyrillic("")).isNull()
        assertThat(romanizer.romanizeCyrillic("abc")).isNull()
        assertThat(romanizer.romanizeCyrillic("е")).isNull()
    }

    @Test
    fun `Hindi and Punjabi keep what they cannot map`() {
        assertThat(romanizer.romanizeHindi("abc 123")).isEqualTo("abc 123")
        assertThat(romanizer.romanizePunjabi("abc 123")).isEqualTo("abc 123")
        assertThat(romanizer.romanizeHindi("")).isEmpty()
        assertThat(romanizer.romanizePunjabi("")).isEmpty()
    }

    @Test
    fun `Hindi and Punjabi words come out in Latin letters`() {
        assertThat(romanizer.romanizeHindi("प्यार")).isEqualTo("pyaar")
        assertThat(romanizer.romanizePunjabi("ਸਤ ਸ੍ਰੀ ਅਕਾਲ")).isEqualTo("st sree akaal")
    }
}
