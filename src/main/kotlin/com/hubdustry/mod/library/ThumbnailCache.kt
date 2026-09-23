package com.hubdustry.mod.library

/** Bounded byte cache for remote previews. The UI owns decoded textures separately. */
class ThumbnailCache(
    private val maxEntries: Int = 24,
    private val maxBytes: Int = Bounds.MAX_THUMBNAIL_BYTES
) {
    private val values = LinkedHashMap<String, ByteArray>(maxEntries, 0.75f, true)
    private var sizeBytes = 0

    @Synchronized fun get(id: String): ByteArray? = values[id]?.copyOf()

    @Synchronized fun put(id: String, bytes: ByteArray) {
        require(bytes.size <= maxBytes) { "thumbnail too large" }
        values.remove(id)?.let { sizeBytes -= it.size }
        values[id] = bytes.copyOf()
        sizeBytes += bytes.size
        while (values.size > maxEntries || sizeBytes > maxBytes) {
            val eldest = values.entries.iterator().next()
            sizeBytes -= eldest.value.size
            values.remove(eldest.key)
        }
    }

    @Synchronized fun clear() {
        values.clear()
        sizeBytes = 0
    }

    @Synchronized fun entryCount(): Int = values.size
    @Synchronized fun byteCount(): Int = sizeBytes
}
