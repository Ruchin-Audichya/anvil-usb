package dev.ruchin.anvil.common

import java.security.MessageDigest

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

fun sha256() : MessageDigest = MessageDigest.getInstance("SHA-256")

/** Constant-time-ish, case-insensitive comparison of two hex digests. */
fun hexDigestEquals(a: String, b: String): Boolean {
    val x = a.trim().lowercase()
    val y = b.trim().lowercase()
    if (x.length != y.length) return false
    var diff = 0
    for (i in x.indices) diff = diff or (x[i].code xor y[i].code)
    return diff == 0
}
