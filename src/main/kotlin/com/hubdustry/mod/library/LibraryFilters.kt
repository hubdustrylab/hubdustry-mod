package com.hubdustry.mod.library

import arc.Core
import arc.scene.ui.TextButton
import arc.scene.ui.layout.Collapser
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import mindustry.gen.Icon
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog

/** Native grouped choices, kept separate from the browsing list. */
class LibraryFilters(
    current: LibraryQuery,
    private val catalog: TagCatalog,
    private val signedIn: Boolean,
    private val tagsOnly: Boolean = false,
    private val apply: (LibraryQuery) -> Unit
) : BaseDialog("@hubdustry.library.filters") {
    private var draft = current
    private val collapsed = mutableMapOf<String, Boolean>()

    init {
        name = "library.filters"
        if (tagsOnly) title.setText("@hubdustry.library.tags")
        setFillParent(true)
        addCloseButton()
        buttons.button("@hubdustry.library.apply", Icon.ok) { apply(draft.copy(offset = 0)); hide() }.size(180f, 64f).get().name = "library.filters.apply"
        LibraryTheme.dialog(this, "HUBDUSTRY / FILTERS")
        shown { rebuild() }
        onResize { rebuild() }
    }

    private fun width() = (Core.graphics.width / Scl.scl(1f) - 40f).coerceIn(240f, 760f)
    private fun text(key: String) = Core.bundle.get("hubdustry.library.$key")

    private fun rebuild() {
        cont.clear()
        val form = Table().apply { top().left().margin(8f) }
        form.button("@hubdustry.library.clear-filters") {
            draft = LibraryQuery(kind = draft.kind, text = draft.text, limit = draft.limit)
            rebuild()
        }.height(44f).left().padBottom(12f).get().name = "library.filters.clear"
        form.row()
        if (!tagsOnly) {
        val sorts = Choices()
        LibrarySort.values().forEach { sort -> sorts.add(text("sort.${sort.name.lowercase()}"), "sort.${sort.name}", { draft.sort == sort }) { draft = draft.copy(sort = sort) } }
        section(form, "sort", text("sort"), sorts.table)

        val ranks = Choices()
        ranks.add(text("all"), "rank.all", { draft.minRank == null }) { draft = draft.copy(minRank = null) }
        RankTier.values().forEach { rank -> ranks.add(text("tier.${rank.name.lowercase()}"), "rank.${rank.name}", { draft.minRank == rank }) { draft = draft.copy(minRank = rank) } }
        section(form, "rank", text("rank-minimum"), ranks.table)
        }

        form.add(text("tags-help")).growX().wrap().left().padTop(12f).padBottom(8f).row()
        for (category in catalog.categories) {
            val choices = Choices()
            category.tags.forEach { tag ->
                choices.add(tag.label.replace("[", "[["), "tag.${tag.id}", { tag.id in draft.tags }) {
                    val others = if (category.multiple) draft.tags else draft.tags.filter { id -> category.tags.none { it.id == id } }
                    draft = draft.copy(tags = if (tag.id in draft.tags) draft.tags - tag.id else (others + tag.id).take(Bounds.MAX_TAGS))
                }
            }
            section(form, "tags.${category.id}", category.label.replace("[", "[["), choices.table)
        }
        if (catalog.categories.isEmpty()) form.add("@hubdustry.library.tags-empty").wrap().growX().left().row()

        if (signedIn && !tagsOnly) {
            val owner = Choices()
            owner.add(text("community"), "owner.all", { !draft.ownerMe }) { draft = draft.copy(ownerMe = false, state = null) }
            owner.add(text("mine"), "owner.me", { draft.ownerMe }) { draft = draft.copy(ownerMe = true) }
            section(form, "owner", text("collection"), owner.table)
            val states = Choices()
            states.add(text("all"), "state.all", { draft.state == null }) { draft = draft.copy(state = null) }
            LibraryState.values().forEach { state -> states.add(text("state.${state.name.lowercase()}"), "state.${state.name}", { draft.state == state }) { draft = draft.copy(ownerMe = true, state = state) } }
            section(form, "state", text("my-status"), states.table)
        }
        cont.pane(form).width(width()).growY().scrollX(false)
        LibraryTheme.controls(cont)
    }

    private fun section(form: Table, id: String, label: String, choices: Table) {
        val collapser = Collapser(choices, collapsed[id] ?: false)
        form.table { header ->
            header.add(label).growX().left()
            val arrow = header.button(Icon.downOpen, Styles.emptyi) {
                collapser.toggle(false); collapsed[id] = collapser.isCollapsed
            }.size(44f).get()
            arrow.name = "library.filters.collapse.$id"
            arrow.update { arrow.image.setDrawable(if (collapser.isCollapsed) Icon.rightOpen else Icon.downOpen) }
        }.growX().padTop(8f).row()
        form.add(collapser).growX().padBottom(8f).row()
    }

    /** Same measured, wrapping square choices as the native Hubdustry browser. */
    private inner class Choices {
        val table = Table().apply { left() }
        private var row = Table().apply { left() }
        private var used = 0f
        init { table.add(row).left().row() }
        fun add(label: String, id: String, checked: () -> Boolean, select: () -> Unit) {
            val button = TextButton(label, Styles.squareTogglet)
            button.name = "library.filters.$id"
            val available = width() - 32f
            val size = (button.prefWidth / Scl.scl(1f) + 24f).coerceIn(72f, available)
            if (used > 0 && used + size + 4f > available) {
                row = Table().apply { left() }; table.add(row).left().row(); used = 0f
            }
            button.label.setEllipsis(true)
            button.clicked { select() }
            button.update { button.isChecked = checked() }
            row.add(button).width(size).height(44f).pad(2f); used += size + 4f
        }
    }
}
