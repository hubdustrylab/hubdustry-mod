package com.hubdustry.mod.library

import arc.Core
import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Lines
import arc.graphics.g2d.TextureRegion
import arc.scene.event.Touchable
import arc.scene.style.Drawable
import arc.scene.ui.Button
import arc.scene.ui.Image
import arc.scene.ui.TextButton
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.util.Scaling
import com.hubdustry.mod.auth.AccountSession
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.ui.Styles
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection

/** Main-menu entry: avatar opens the library, Login/name opens the account panel. */
internal class MenuAccountEntry(
    private val session: () -> AccountSession?,
    private val executor: ExecutorService,
    private val logo: Drawable,
    openBrowser: () -> Unit,
    openAccount: () -> Unit,
) : AutoCloseable {
    private val root = Table()
    private val avatar = object : Image(logo) {
        init { setScaling(Scaling.fit) }
        override fun draw() {
            super.draw()
            val opacity = parentAlpha * color.a * if (browserButton.isOver) 1f else .65f
            val gap = Scl.scl(5f)
            corners(x - gap, y - gap, x + width + gap, y + height + gap, opacity)
            Draw.color(LibraryTheme.accent, opacity * .08f); Lines.stroke(Scl.scl(1f))
            for (i in 1..5) Lines.line(x, y + height * i / 6f, x + width, y + height * i / 6f)
            Draw.reset()
        }
    }
    private val browserButton = Button(Styles.emptyi)
    private val accountButton = TextButton("", LibraryTheme.button().apply {
        up = null; checked = null; checkedOver = null; disabled = null
        over = LibraryTheme.fill(LibraryTheme.hover); down = over
        fontColor = LibraryTheme.accent; overFontColor = Color.white; downFontColor = Color.white
    })
    private var label = ""
    private var avatarKey: String? = null
    private var texture: Texture? = null
    private var request: Future<*>? = null
    @Volatile private var connection: HttpsURLConnection? = null
    @Volatile private var generation = 0L
    @Volatile private var closed = false

    init {
        root.name = "hubdustry.menu.account"
        root.setFillParent(true); root.touchable = Touchable.childrenOnly
        root.bottom()
        root.table { content ->
            content.background(ArchiveUi.panel(ArchiveUi.black, LibraryTheme.hover, cut = true))
            content.margin(8f)
            browserButton.name = "hubdustry.menu.open"
            browserButton.margin(0f)
            avatar.name = "hubdustry.menu.avatar"
            browserButton.add(avatar).size(60f).pad(8f)
            browserButton.clicked { if (!closed) openBrowser() }
            content.add(browserButton).width(192f).height(82f).tooltip("Hubdustry").row()
            content.image(LibraryTheme.fill(LibraryTheme.hover)).width(192f).height(1f).row()
            accountButton.name = "hubdustry.menu.login"
            accountButton.label.setEllipsis(true)
            accountButton.clicked { if (!closed) openAccount() }
            content.add(accountButton).width(192f).height(38f)
        }.padBottom(18f)
        root.update { updateIdentity() }
        // Menu visibility is owned by the game; dialogs stay above this entry.
        Vars.ui.menuGroup.addChild(root)
        updateIdentity()
    }

    private fun updateIdentity() {
        if (closed) return
        val current = session()
        val nextLabel = current?.displayName?.takeIf { it.isNotBlank() }?.replace("[", "[[")
            ?: Core.bundle.get(if (current == null) "hubdustry.menu.login" else "hubdustry.account")
        if (label != nextLabel) {
            label = nextLabel
            accountButton.style.font = LibraryTheme.label(text = label).font
            accountButton.setStyle(accountButton.style)
            accountButton.setText(label)
        }
        val nextAvatar = if (current == null) "signed-out" else current.avatarRef.orEmpty()
        if (nextAvatar == avatarKey) return
        avatarKey = nextAvatar; val version = ++generation
        request?.cancel(true); connection?.disconnect(); connection = null
        avatar.setDrawable(if (current == null) logo else Icon.players)
        texture?.dispose(); texture = null
        val url = avatarRequest(nextAvatar) ?: return
        request = executor.submit { loadAvatar(url, version) }
    }

    private fun loadAvatar(url: String, version: Long) {
        var transport: HttpsURLConnection? = null
        try {
            if (closed || version != generation) return
            transport = URI.create(url).toURL().openConnection() as HttpsURLConnection
            val active = transport
            connection = active
            active.instanceFollowRedirects = false; active.connectTimeout = 5000; active.readTimeout = 5000
            active.setRequestProperty("Accept", "image/png")
            if (active.responseCode != 200 || active.contentLengthLong > 131072) return
            val bytes = active.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > 131072) return
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (!boundedPng(bytes) || closed || version != generation) return
            val pixmap = Pixmap(bytes)
            Core.app.post {
                try {
                    if (!closed && version == generation) {
                        texture = Texture(pixmap)
                        avatar.setDrawable(TextureRegion(texture))
                    }
                } finally { pixmap.dispose() }
            }
        } catch (_: Exception) { /* Keep the usable local avatar when the CDN is unavailable. */ }
        finally { transport?.disconnect(); if (connection === transport) connection = null }
    }

    override fun close() {
        if (closed) return
        closed = true; generation++; request?.cancel(true); connection?.disconnect()
        root.remove(); texture?.dispose(); texture = null
    }

    companion object {
        internal fun avatarRequest(raw: String): String? = runCatching {
            if (raw.length > 512) return null
            val uri = URI.create(raw)
            if (uri.scheme != "https" || uri.host != "cdn.discordapp.com" || uri.port != -1 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
                || !uri.rawPath.matches(Regex("/avatars/[0-9]{1,24}/(?:a_)?[a-fA-F0-9]{16,64}\\.png"))) return null
            "$raw?size=64"
        }.getOrNull()

        internal fun boundedPng(bytes: ByteArray): Boolean {
            if (bytes.size !in 33..131072) return false
            val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82)
            if (signature.indices.any { bytes[it] != signature[it] }) return false
            return ByteBuffer.wrap(bytes, 16, 4).int in 1..256 && ByteBuffer.wrap(bytes, 20, 4).int in 1..256
        }

        private fun corners(left: Float, bottom: Float, right: Float, top: Float, opacity: Float) {
            val corner = Scl.scl(10f)
            Draw.color(LibraryTheme.accent, opacity); Lines.stroke(Scl.scl(1.3f))
            Lines.line(left, top - corner, left, top); Lines.line(left, top, left + corner, top)
            Lines.line(right - corner, top, right, top); Lines.line(right, top, right, top - corner)
            Lines.line(left, bottom + corner, left, bottom); Lines.line(left, bottom, left + corner, bottom)
            Lines.line(right - corner, bottom, right, bottom); Lines.line(right, bottom, right, bottom + corner)
            Draw.reset()
        }
    }
}
