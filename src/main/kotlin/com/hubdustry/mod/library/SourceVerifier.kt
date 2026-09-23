package com.hubdustry.mod.library

import java.security.MessageDigest

object SourceVerifier {
    fun verify(bytes: ByteArray, expectedSha256: String, declaredSize: Long): ByteArray {
        require(declaredSize in 1..Bounds.MAX_SOURCE_BYTES) { "source size out of bounds" }
        require(bytes.size.toLong() == declaredSize) { "source size mismatch" }
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "invalid digest" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual.equals(expectedSha256, ignoreCase = true)) { "source digest mismatch" }
        return bytes.copyOf()
    }

    fun verifyThumbnail(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= Bounds.MAX_THUMBNAIL_BYTES) { "thumbnail too large" }
        require(bytes.size >= 24 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() && bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() && bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte()) { "unsupported thumbnail" }
        require(bytes.sliceArray(8..15).contentEquals(byteArrayOf(0, 0, 0, 13, 73, 72, 68, 82))) { "invalid PNG header" }
        val width = ((bytes[16].toInt() and 0xff) shl 24) or ((bytes[17].toInt() and 0xff) shl 16) or ((bytes[18].toInt() and 0xff) shl 8) or (bytes[19].toInt() and 0xff)
        val height = ((bytes[20].toInt() and 0xff) shl 24) or ((bytes[21].toInt() and 0xff) shl 16) or ((bytes[22].toInt() and 0xff) shl 8) or (bytes[23].toInt() and 0xff)
        require(width in 1..2048 && height in 1..2048) { "thumbnail dimensions out of bounds" }
        return bytes.copyOf()
    }
}
