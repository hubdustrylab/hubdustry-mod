package com.hubdustry.mod.library

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuAvatarTest {
    @Test fun onlyPublicDiscordPngCanBeRequested() {
        val path = "/avatars/123/0123456789abcdef0123456789abcdef.png"
        assertEquals("https://cdn.discordapp.com$path?size=64", MenuAccountEntry.avatarRequest("https://cdn.discordapp.com$path"))
        for (url in listOf("http://cdn.discordapp.com$path", "https://example.com$path", "https://cdn.discordapp.com.evil.test$path", "https://user@cdn.discordapp.com$path", "https://cdn.discordapp.com:444$path", "https://cdn.discordapp.com$path?token=secret", "https://cdn.discordapp.com$path#fragment", "https://cdn.discordapp.com/../avatar.png", "file:///avatar.png")) {
            assertNull(MenuAccountEntry.avatarRequest(url))
        }
    }

    @Test fun imageHeaderAndDecodedDimensionsAreBoundedBeforeDecoding() {
        val png = ByteArray(33)
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82).copyInto(png)
        ByteBuffer.wrap(png, 16, 8).putInt(64).putInt(64)
        assertTrue(MenuAccountEntry.boundedPng(png))
        assertFalse(MenuAccountEntry.boundedPng(png.copyOf(131073)))
        ByteBuffer.wrap(png, 16, 4).putInt(257)
        assertFalse(MenuAccountEntry.boundedPng(png))
        ByteBuffer.wrap(png, 16, 4).putInt(0)
        assertFalse(MenuAccountEntry.boundedPng(png))
    }
}
