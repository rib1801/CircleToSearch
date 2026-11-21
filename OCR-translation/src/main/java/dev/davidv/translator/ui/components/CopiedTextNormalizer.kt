package dev.davidv.translator.ui.components

private val URL_RE = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s]+$""")
private val EMAIL_RE = Regex("""^[^\s@]+@[^\s@]+\.[a-zA-Z]{2,}$""")
private val NUMBER_RE = Regex("""^\d{10,11}$""")
private val SPACED_URL_RE = Regex("""^\s*[A-Za-z]{2,8}\s*:\s*/\s*/""")
private val SPACED_EMAIL_RE = Regex("""^\s*[^\s@]{1,3}\s*@\s*[^\s@.]{1,3}""")
private val WHITESPACE_RE = Regex("""\s+""")
private val NUMBER_SEPARATORS_RE = Regex("""[.\-/()+]""")

private fun isValidStructured(s: String): Boolean =
  URL_RE.matches(s) || EMAIL_RE.matches(s) || NUMBER_RE.matches(s)

/**
 * Some source apps (e.g. Instagram comments) serialize a link as one span per
 * character, and the clipboard inserts a space at every span boundary. This
 * rebuilds URL / e-mail / CPF-phone text in that case and otherwise returns the
 * trimmed text untouched.
 */
fun normalizeCopiedText(raw: String): String {
  val t = raw.trim()
  if (t.isEmpty() || isValidStructured(t)) return t

  val tokens = t.split(WHITESPACE_RE).filter { it.isNotEmpty() }
  val spaced =
    SPACED_URL_RE.containsMatchIn(t) ||
      SPACED_EMAIL_RE.containsMatchIn(t) ||
      (tokens.isNotEmpty() && tokens.count { it.length == 1 } * 100 > tokens.size * 40)
  if (!spaced) return t

  val compact = t.replace(WHITESPACE_RE, "")
  if (isValidStructured(compact)) return compact
  val digits = compact.replace(NUMBER_SEPARATORS_RE, "")
  if (NUMBER_RE.matches(digits)) return digits
  return t
}
