package com.hubdustry.mod.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.security.MessageDigest

class LibraryCoreTest {
    @Test fun queryEncodingIsBoundedAndDeterministic() {
        val encoded = LibraryQueryEncoder.encode(LibraryQuery(ContentKind.MAP, "iron belt", listOf("base", "fast"), sort = LibrarySort.NAME, offset = 12, limit = 24))
        assertTrue(encoded.contains("kind=MAP"))
        assertTrue(encoded.contains("q=iron%20belt"))
        assertTrue(encoded.contains("tags=base%2Cfast"))
        assertTrue(encoded.endsWith("offset=12&limit=24"))
    }

    @Test fun invalidBoundsAreRejected() {
        assertFailsWith<IllegalArgumentException> { LibraryQuery(limit = 51) }
        assertFailsWith<IllegalArgumentException> { LibraryQuery(offset = 10001) }
        assertFailsWith<IllegalArgumentException> { Bounds.requireMetadata("", "", emptyList()) }
    }

    @Test fun sourceDigestAndSizeAreVerified() {
        val bytes = byteArrayOf(1, 2, 3)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(bytes.toList(), SourceVerifier.verify(bytes, digest, 3).toList())
        assertFailsWith<IllegalArgumentException> { SourceVerifier.verify(bytes, digest, 4) }
        assertFailsWith<IllegalArgumentException> { SourceVerifier.verify(bytes, "0".repeat(64), 3) }
    }

    @Test fun thumbnailCacheIsBoundedAndCopiesBytes() {
        val cache = ThumbnailCache(maxEntries = 2, maxBytes = 8)
        val first = byteArrayOf(1, 2, 3, 4)
        cache.put("a", first)
        first[0] = 9
        assertEquals(1, cache.get("a")!![0])
        cache.put("b", byteArrayOf(5, 6, 7, 8))
        cache.put("c", byteArrayOf(9, 10, 11, 12))
        assertNull(cache.get("a"))
        assertEquals(2, cache.entryCount())
        assertEquals(8, cache.byteCount())
    }

    @Test fun pngVerifierChecksCompleteSignatureAndDimensionsBeforeDecode() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1)
        assertEquals(24, SourceVerifier.verifyThumbnail(png).size)
        assertFailsWith<IllegalArgumentException> { SourceVerifier.verifyThumbnail(byteArrayOf(0x89.toByte(), 0x50, 0, 0, 0, 0, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { SourceVerifier.verifyThumbnail(png.copyOf().also { it[19] = 0 }) }
        assertFailsWith<IllegalArgumentException> { SourceVerifier.verifyThumbnail(png.copyOf().also { it[12] = 0 }) }
        assertFailsWith<IllegalArgumentException> { SourceVerifier.verifyThumbnail(png.copyOf().also { it[18] = 9 }) }
    }
}
