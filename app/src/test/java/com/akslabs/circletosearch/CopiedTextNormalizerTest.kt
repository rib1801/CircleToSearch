package com.akslabs.circletosearch

import dev.davidv.translator.ui.components.normalizeCopiedText
import org.junit.Assert.assertEquals
import org.junit.Test

class CopiedTextNormalizerTest {
  @Test
  fun instagramSpacedUrl() {
    val raw = "h t t p s : / / w w w . i n s t a g r a m . c o m / r e e l / D f _ I o F g B I X M / ? g w p x = M X Z 5 M n N j Z 3 Q x O T h i d A = ="
    assertEquals("https://www.instagram.com/reel/Df_IoFgBIXM/?gwpx=MXZ5MnNjZ3QxOThidA==", normalizeCopiedText(raw))
  }

  @Test
  fun plainSentenceUnchanged() {
    assertEquals("o carro bateu na mao", normalizeCopiedText("o carro bateu na mao"))
  }

  @Test
  fun spacedEmail() {
    assertEquals("jesse@gmail.com", normalizeCopiedText("j e s s e @ g m a i l . c o m"))
  }

  @Test
  fun spacedCpf() {
    assertEquals("12345678901", normalizeCopiedText("1 2 3 . 4 5 6 . 7 8 9 - 0 1"))
  }

  @Test
  fun multilineSentenceIntact() {
    val s = "Primeira linha do texto\ne a segunda linha\n\ncom um paragrafo a mais"
    assertEquals(s, normalizeCopiedText(s))
  }

  @Test
  fun normalUrlWithSurroundingSpaces() {
    assertEquals("https://example.com/a?b=c", normalizeCopiedText("  https://example.com/a?b=c \n"))
  }
}
