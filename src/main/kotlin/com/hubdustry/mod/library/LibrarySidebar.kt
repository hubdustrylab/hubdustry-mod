package com.hubdustry.mod.library

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Button
import arc.scene.ui.Image
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import arc.util.Scaling
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.ui.Styles

/** Shared archive navigation: desktop rail or the narrow-screen navigation sheet. */
internal class LibrarySidebar(
    kind: ContentKind,
    private val expanded: Boolean,
    overlay: Boolean,
    onToggle: () -> Unit,
    onNavigate: (ContentKind) -> Unit,
    onAccount: () -> Unit,
    onReturn: () -> Unit,
) : Table() {
    init {
        name = "library.sidebar"
        background(LibraryTheme.fill(ArchiveUi.black))
        margin(8f)

        val navigation = Table().apply { top() }
        navigation.table { header ->
            header.left().marginTop(16f).marginBottom(28f)
            val logo = Vars.mods.getMod("hubdustry")?.iconTexture
                ?.let { TextureRegionDrawable(TextureRegion(it)) } ?: Icon.players
            header.add(Image(logo).apply { setScaling(Scaling.fit) }).size(if (expanded) 48f else 40f)
                .padLeft(if (expanded) 8f else 0f)
            if (expanded) {
                header.table { brand ->
                    brand.left()
                    brand.add(ArchiveUi.text("HUB", 29f, bold = true)).left().row()
                    brand.add(ArchiveUi.text("DUSTRY", 23f, bold = true)).left().padTop(-8f)
                }.growX().padLeft(10f)
            }
            if (overlay) header.button(Icon.cancel, LibraryTheme.icon()) { onToggle() }
                .size(36f).tooltip("@hubdustry.back").get().name = "library.sidebar.close"
        }.growX().row()

        if (overlay) {
            navigation.add(rowButton("hubdustry.account", Icon.players, action = onAccount).apply { name = "library.account" })
                .growX().height(44f).padBottom(16f).row()
        }
        for (entry in ContentKind.values()) {
            val label = if (entry == ContentKind.SCHEMATIC) "hubdustry.archive.schematics" else "hubdustry.archive.maps"
            val icon = if (entry == ContentKind.SCHEMATIC) Icon.book else Icon.map
            navigation.add(rowButton(label, icon, selected = entry == kind) { onNavigate(entry) }.apply {
                name = "library.navigate.${entry.name}"
            }).growX().height(54f).padBottom(6f).row()
        }
        // Only the upper area scrolls on short windows; account and return stay reachable.
        add(ScrollPane(navigation, Styles.noBarPane).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }).grow().minHeight(0f).row()

        table { footer ->
            footer.top()
            if (!overlay) {
                footer.table { utility ->
                    utility.background(LibraryTheme.fill(LibraryTheme.paper))
                    utility.add(rowButton("hubdustry.account", Icon.players, action = onAccount).apply {
                        name = "library.account"
                    }).growX().height(44f).row()
                    utility.image(LibraryTheme.fill(LibraryTheme.line)).height(1f).growX().padLeft(10f).padRight(10f).row()
                    utility.add(rowButton(if (expanded) "hubdustry.sidebar.collapse" else "hubdustry.sidebar.expand",
                        if (expanded) Icon.left else Icon.menu, action = onToggle).apply {
                        name = "library.sidebar.toggle"
                    }).growX().height(44f)
                }.growX().padBottom(12f).row()
            }
            footer.add(rowButton("hubdustry.sidebar.return", Icon.play, primary = true, action = onReturn).apply {
                name = "library.sidebar.return"
            }).growX().height(48f).row()
            footer.add(ArchiveUi.text(if (expanded) "HUBDUSTRY / COMMUNITY" else "H /", 10f, LibraryTheme.muted))
                .padTop(14f).padBottom(8f)
        }.growX().padTop(16f)
    }

    private fun rowButton(
        labelKey: String,
        icon: Drawable,
        selected: Boolean = false,
        primary: Boolean = false,
        action: () -> Unit,
    ): Button {
        val label = Core.bundle.get(labelKey)
        val button = Button(Button.ButtonStyle().apply {
            up = when {
                selected -> LibraryTheme.fill(LibraryTheme.accent)
                primary -> ArchiveUi.panel(ArchiveUi.black, LibraryTheme.line, technical = true)
                else -> LibraryTheme.fill(Color.clear)
            }
            over = LibraryTheme.fill(if (selected) LibraryTheme.accent else LibraryTheme.hover)
            down = LibraryTheme.fill(LibraryTheme.accent)
        }).apply { margin(0f); left(); clicked(action); addListener(arc.scene.ui.Tooltip { it.add(label) }) }
        val marker = button.image(LibraryTheme.fill(if (selected) LibraryTheme.onAccent else Color.clear))
            .width(3f).growY().get()
        val symbol = Image(icon)
        button.add(symbol).size(26f).pad(if (expanded) 11f else 10f).apply { if (!expanded) expandX() }
        val text = if (expanded) ArchiveUi.text(label, 17f) else null
        if (text != null) {
            button.image(LibraryTheme.fill(if (selected) LibraryTheme.onAccent else LibraryTheme.line))
                .width(1f).height(26f).padRight(14f)
            button.add(text).growX().minWidth(0f).ellipsis(true).left()
            button.image(Icon.right).size(12f).padLeft(8f).padRight(12f)
                .color(if (selected) LibraryTheme.onAccent else LibraryTheme.muted)
        }
        button.update {
            val foreground = if (selected || button.isPressed) LibraryTheme.onAccent else if (button.isOver || primary) LibraryTheme.ink else LibraryTheme.muted
            symbol.setColor(foreground)
            text?.setColor(if (selected || button.isPressed) LibraryTheme.onAccent else LibraryTheme.ink)
            marker.color.a = if (selected) 1f else 0f
        }
        return button
    }
}
