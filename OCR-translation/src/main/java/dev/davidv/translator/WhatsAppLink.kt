package dev.davidv.translator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Brazilian phone detection for the WhatsApp action. Deliberately conservative:
 * when in doubt returns null (better not to offer than to open a wrong chat).
 * Unrelated to normalizeCopiedText (that one is for copied text).
 *
 * Returns digits only, with the leading 55, ready for https://wa.me/<number>.
 */
fun brWhatsAppNumber(raw: String): String? {
  val original = raw.trim()
  if (original.isEmpty() || original.length > 24) return null
  // Only phone-like characters; '.' and '/' are excluded on purpose (CPF/CNPJ masks).
  if (original.any { !(it.isDigit() || it in " ()-+") }) return null
  val digits = original.filter { it.isDigit() }
  if (digits.length !in 10..13) return null

  val plus55 = original.startsWith("+55")
  val formatted = plus55 || original.any { it in "() -" }
  val rawDigitsOnly = original.all { it.isDigit() }
  if (original.contains('+') && !plus55) return null

  if (rawDigitsOnly) {
    when (digits.length) {
      11 -> if (isValidCpf(digits)) return null
      10 -> return null // ambiguous without any formatting
    }
  } else if (!formatted) {
    return null
  }

  val hasCountry = digits.startsWith("55") && digits.length >= 12
  val national = if (hasCountry) digits.substring(2) else digits
  if (!hasCountry && digits.length > 11) return null
  if (national.length !in 10..11) return null

  val ddd = national.substring(0, 2).toInt()
  if (ddd !in 11..99 || national[1] == '0') return null
  val subscriber = national.substring(2)
  if (subscriber.length == 9) {
    if (subscriber[0] != '9') return null // mobile numbers have the 9th digit
  } else if (subscriber[0] !in '2'..'9') {
    return null
  }
  // Raw 11 digits without "55": the third digit must be '9' (already enforced above).
  return "55$national"
}

private fun isValidCpf(d: String): Boolean {
  if (d.length != 11 || d.all { it == d[0] }) return false
  fun dv(len: Int): Int {
    var sum = 0
    for (i in 0 until len) sum += (d[i] - '0') * (len + 1 - i)
    val r = (sum * 10) % 11
    return if (r == 10) 0 else r
  }
  return dv(9) == d[9] - '0' && dv(10) == d[10] - '0'
}

/** Opens a WhatsApp chat (WhatsApp, then Business, then browser). Only called on tap. */
fun openWhatsAppChat(
  context: Context,
  number: String,
) {
  val uri = Uri.parse("https://wa.me/$number")
  val packages = listOf("com.whatsapp", "com.whatsapp.w4b", null)
  for (pkg in packages) {
    val ok =
      runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        context.startActivity(intent)
      }.isSuccess
    if (ok) return
  }
  Toast.makeText(context, "WhatsApp indisponível", Toast.LENGTH_SHORT).show()
}
