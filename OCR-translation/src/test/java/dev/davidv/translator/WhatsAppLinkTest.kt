package dev.davidv.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppLinkTest {
  @Test
  fun accepts() {
    assertEquals("5511987654321", brWhatsAppNumber("(11) 98765-4321"))
    assertEquals("5511987654321", brWhatsAppNumber("11987654321"))
    assertEquals("5511987654321", brWhatsAppNumber("+55 11 98765-4321"))
    assertEquals("5511987654321", brWhatsAppNumber("5511987654321"))
    assertEquals("551133334444", brWhatsAppNumber("(11) 3333-4444"))
    assertEquals("551133334444", brWhatsAppNumber("551133334444"))
    assertEquals("5521987654321", brWhatsAppNumber("  21 98765 4321 "))
  }

  @Test
  fun rejects() {
    assertNull(brWhatsAppNumber("12345678901"))
    assertNull(brWhatsAppNumber("529.982.247-25"))
    assertNull(brWhatsAppNumber("52998224725")) // valid CPF
    assertNull(brWhatsAppNumber("11144477735")) // valid CPF
    assertNull(brWhatsAppNumber("11333344445")) // 3rd digit not 9
    assertNull(brWhatsAppNumber("1133334444")) // raw 10 digits
    assertNull(brWhatsAppNumber("(01) 98765-4321"))
    assertNull(brWhatsAppNumber("(11) 88765-4321"))
    assertNull(brWhatsAppNumber("+1 415 555 2671"))
    assertNull(brWhatsAppNumber("12.345.678/0001-95"))
    assertNull(brWhatsAppNumber("abc 11 98765-4321"))
    assertNull(brWhatsAppNumber("2024-11-22"))
    assertNull(brWhatsAppNumber(""))
  }
}
