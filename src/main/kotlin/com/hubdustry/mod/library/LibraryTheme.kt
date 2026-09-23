package com.hubdustry.mod.library

import arc.Core
import arc.Events
import arc.freetype.FreeTypeFontGenerator
import arc.graphics.Color
import arc.graphics.Texture.TextureFilter
import arc.graphics.g2d.Font
import arc.graphics.g2d.PixmapPacker
import arc.scene.Element
import arc.scene.Group
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.*
import arc.scene.ui.layout.Scl
import arc.util.Align
import mindustry.Vars
import mindustry.game.EventType.DisposeEvent
import mindustry.ui.Fonts
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog

/** Hubdustry-only styles; never changes the game's shared style registry. */
object LibraryTheme {
    val canvas = Color.valueOf("191919")
    val paper = Color.valueOf("1f1f1f")
    val ink = Color.valueOf("eeeeee")
    val muted = Color.valueOf("aaaaaa")
    val line = Color.valueOf("383838")
    val yellow = Color.valueOf("fffa00")
    val hover = Color.valueOf("30302c")
    val onAccent = Color.valueOf("191919")

    private val ownedFonts = mutableListOf<Pair<Font, FreeTypeFontGenerator>>()
    private val body: Font by lazy { font("Barlow-Regular.ttf", 20) }
    private val bold: Font by lazy { font("Barlow-Bold.ttf", 28) }
    private val display: Font by lazy { font("Barlow-Bold.ttf", 72) }

    init { Events.on(DisposeEvent::class.java) {
        ownedFonts.forEach { (font, generator) ->
            font.dispose()
            (font.data as? FreeTypeFontGenerator.FreeTypeFontData)?.dispose()
            generator.dispose()
        }
        ownedFonts.clear()
    } }

    private fun font(file: String, size: Int): Font {
        val generator = FreeTypeFontGenerator(Vars.mods.getMod("hubdustry").root.child("fonts").child(file))
        val parameters = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
            this.size = Scl.scl(size.toFloat()).toInt().coerceAtLeast(size)
            incremental = true
            shadowOffsetY = 0
            minFilter = TextureFilter.linear; magFilter = TextureFilter.linear
            packer = PixmapPacker(1024, 1024, 2, false)
        }
        return try {
            generator.generateFont(parameters).also { it.setOwnsTexture(true); ownedFonts.add(it to generator) }
        } catch (error: Throwable) {
            parameters.packer.dispose(); generator.dispose(); throw error
        }
    }

    fun fill(color: Color) = TextureRegionDrawable(Core.atlas.white()).tint(color)
    private fun fontFor(text: CharSequence, heading: Boolean = false): Font =
        if (text.any { it.code > 0x024f && it.code !in 0x1e00..0x1eff && it.code !in 0x2000..0x206f }) Fonts.def else if (heading) bold else body

    fun label(heading: Boolean = false, color: Color = ink, text: CharSequence = "") = Label.LabelStyle(fontFor(text, heading), color)
    fun displayLabel(color: Color, text: CharSequence): Label.LabelStyle? =
        if (fontFor(text, true) === Fonts.def) null else Label.LabelStyle(display, color)
    fun width() = (Core.graphics.width / Scl.scl(1f) - 52f).coerceIn(240f, 1280f)

    fun button(primary: Boolean = false, selection: Boolean = false) = TextButton.TextButtonStyle(Styles.defaultt).apply {
        font = body
        fontColor = if (primary) onAccent else ink
        downFontColor = onAccent; overFontColor = fontColor
        checkedFontColor = if (primary || selection) onAccent else ink
        disabledFontColor = muted
        up = ArchiveUi.panel(if (primary) yellow else paper, if (primary) yellow else line, cut = true)
        over = ArchiveUi.panel(if (primary) Color.valueOf("e6e100") else hover, yellow, cut = true)
        down = ArchiveUi.panel(yellow, yellow, cut = true); checked = if (selection) down else up; checkedOver = if (selection) down else over
        disabled = fill(canvas)
    }

    fun icon() = ImageButton.ImageButtonStyle(Styles.emptyi).apply {
        up = fill(paper); over = fill(hover); down = fill(yellow); checked = up
        imageUpColor = ink; imageOverColor = ink; imageDownColor = onAccent; imageCheckedColor = ink
        imageDisabledColor = muted
    }

    fun card() = Button.ButtonStyle(Styles.grayt).apply {
        up = fill(paper); over = fill(hover); down = fill(line); checked = fill(hover)
    }

    /** Restyle native children without touching preview images or global defaults. */
    fun controls(element: Element) {
        when (element) {
            is TextButton -> {
                val selection = element.name?.let { name -> name.startsWith("library.sort.choice.") || listOf("sort", "rank", "tag", "owner", "state").any { name.startsWith("library.filters.$it.") } } == true
                val primary = element.name == "library.import" || element.name == "library.filters.apply"
                element.style = button(primary, selection)
                element.style.font = fontFor(element.text)
                element.forEach { child -> if (child is Image) child.update { child.setColor(if (primary || element.isPressed || selection && element.isChecked) onAccent else ink) } }
            }
            is ImageButton -> element.style = ImageButton.ImageButtonStyle(icon()).apply { imageUp = element.style.imageUp }
            is TextField -> {
                element.setOnlyFontChars(false)
                element.style = TextField.TextFieldStyle(element.style).apply {
                font = body; messageFont = body
                fontColor = ink; focusedFontColor = ink; messageFontColor = muted
                background = fill(paper); focusedBackground = fill(paper)
                cursor = fill(yellow).apply { minWidth = 2f }
                selection = fill(Color.valueOf("5a5700"))
                }
                element.update {
                    val wanted = fontFor(element.text)
                    if (element.style.font !== wanted) element.style = TextField.TextFieldStyle(element.style).apply { font = wanted }
                }
            }
            is Label -> {
                val tone = when (element.name) { "library.label.accent" -> yellow; "library.label.secondary" -> muted; else -> ink }
                element.style = label(color = tone, text = element.text); element.setColor(Color.white)
            }
            is ScrollPane -> element.style = ScrollPane.ScrollPaneStyle(element.style).apply {
                vScroll = fill(line).apply { minWidth = 4f }
                vScrollKnob = fill(muted).apply { minWidth = 4f; minHeight = 32f }
            }
        }
        if (element is Group) element.children.forEach { controls(it) }
    }

    fun dialog(dialog: BaseDialog, section: String = "HUBDUSTRY") {
        dialog.background(fill(canvas))
        dialog.buttons.children.filterIsInstance<TextButton>().forEach {
            if (it.text.toString() == Core.bundle.get("back")) it.setText("@hubdustry.back")
        }
        controls(dialog.cont); controls(dialog.buttons)
        dialog.title.style = label(true, text = dialog.title.text)
        dialog.title.setColor(Color.white)
        dialog.title.setAlignment(Align.left)
        dialog.title.setFontScale(1.25f)
        dialog.titleTable.clear()
        val header = dialog.titleTable.table { row ->
            row.left().marginTop(14f).marginBottom(12f)
            row.image(ArchiveUi.stripes()).width(8f).height(58f).padRight(20f)
            row.table { text ->
                text.add(section, label(false, muted)).fontScale(.7f).left().row()
                text.add(dialog.title).growX().minWidth(0f).left()
            }.growX().left()
        }.width(width())
        dialog.titleTable.row()
        dialog.titleTable.image(fill(line)).height(1f).growX()
        var compact: Boolean? = null
        dialog.update {
            val available = width()
            header.width(available)
            val narrow = available < 600f
            if (compact != narrow) {
                compact = narrow
                dialog.title.setFontScale(if (narrow) .8f else 1.25f)
                dialog.titleTable.invalidateHierarchy()
            }
        }
        // ModBrowserDialog stretches this table over the content for its footer overlay.
        // Keep it transparent so it cannot cover the browser underneath.
        dialog.buttons.margin(10f)
    }
}
