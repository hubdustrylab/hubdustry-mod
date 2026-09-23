package com.hubdustry.mod.library

import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.graphics.g2d.Fill
import arc.graphics.g2d.Lines
import arc.scene.style.BaseDrawable
import arc.scene.ui.Button
import arc.scene.ui.Label
import arc.scene.ui.TextButton

/** Native archive components, independent of the game's browser/dialog chrome. */
object ArchiveUi {
    val bone = Color.valueOf("e7e7df")
    val black = Color.valueOf("111313")
    val grid = Color.valueOf("292d2c")

    fun text(value: String, size: Float = 20f, color: Color = LibraryTheme.ink, bold: Boolean = false): Label {
        val display = if (bold && size > 32f) LibraryTheme.displayLabel(color, value) else null
        return Label(value, display ?: LibraryTheme.label(bold, color, value)).apply {
            setFontScale(size / if (display != null) 72f else if (bold) 28f else 20f)
        }
    }

    fun panel(tone: Color, edge: Color = LibraryTheme.line, technical: Boolean = false, cut: Boolean = false) = object : BaseDrawable() {
        override fun draw(x: Float, y: Float, width: Float, height: Float) {
            val alpha = Draw.getColor().a
            Draw.color(tone, alpha)
            Fill.rect(x + width / 2f, y + height / 2f, width, height)
            if (technical) {
                Draw.color(grid, alpha * .62f)
                Lines.stroke(1f)
                var px = x + 24f
                while (px < x + width - 1f) { Lines.line(px, y + 1f, px, y + height - 1f); px += 40f }
                var py = y + 24f
                while (py < y + height - 1f) { Lines.line(x + 1f, py, x + width - 1f, py); py += 40f }
            }
            Draw.color(edge, alpha)
            Lines.stroke(1f)
            Lines.line(x, y + height - 1f, x + width, y + height - 1f)
            Lines.line(x, y, x + width, y)
            if (technical) {
                Lines.stroke(2f)
                for (cx in floatArrayOf(x + 1f, x + width - 1f)) {
                    val direction = if (cx < x + width / 2f) 1f else -1f
                    Lines.line(cx, y + height - 1f, cx + 14f * direction, y + height - 1f)
                    Lines.line(cx, y + height - 1f, cx, y + height - 15f)
                }
            }
            if (cut) {
                Draw.color(LibraryTheme.canvas, alpha)
                Fill.tri(x + width - 12f, y + height, x + width, y + height - 12f, x + width, y + height)
                Draw.color(edge, alpha)
                Lines.line(x + width - 12f, y + height - 1f, x + width - 1f, y + height - 12f)
            }
            Lines.stroke(1f)
            Draw.color()
        }
    }

    fun card(feature: Boolean = false) = Button.ButtonStyle().apply {
        up = panel(if (feature) black else LibraryTheme.paper, if (feature) LibraryTheme.muted else LibraryTheme.line, feature, true)
        over = panel(LibraryTheme.hover, LibraryTheme.yellow, feature, true)
        down = panel(LibraryTheme.line, LibraryTheme.yellow, feature, true)
        checked = over
    }

    fun navigation(selected: Boolean) = TextButton.TextButtonStyle(LibraryTheme.button()).apply {
        up = panel(if (selected) LibraryTheme.yellow else bone, if (selected) LibraryTheme.yellow else Color.valueOf("c7c8c0"))
        over = panel(LibraryTheme.yellow, black)
        down = over; checked = up
        fontColor = black; overFontColor = black; downFontColor = black; checkedFontColor = black
    }

    fun stripes() = object : BaseDrawable() {
        override fun draw(x: Float, y: Float, width: Float, height: Float) {
            val alpha = Draw.getColor().a
            Draw.color(LibraryTheme.yellow, alpha)
            Fill.rect(x + width / 2f, y + height / 2f, width, height)
            Draw.color(black, alpha)
            Lines.stroke(2f)
            var px = x + height
            while (px < x + width) { Lines.line(px - height, y, px, y + height); px += 9f }
            Lines.stroke(1f); Draw.color()
        }
    }

    fun icon(symbol: String) = object : BaseDrawable() {
        init { minWidth = 24f; minHeight = 24f }
        override fun draw(x: Float, y: Float, width: Float, height: Float) {
            fun line(ax: Float, ay: Float, bx: Float, by: Float) = Lines.line(x + ax * width, y + ay * height, x + bx * width, y + by * height)
            Lines.stroke(width / 12f)
            when (symbol) {
                "search" -> { Lines.circle(x + width * .42f, y + height * .58f, width * .27f); line(.62f, .38f, .88f, .12f) }
                "filter" -> { line(.12f, .82f, .88f, .82f); line(.12f, .82f, .43f, .48f); line(.88f, .82f, .57f, .48f); line(.43f, .48f, .43f, .16f); line(.57f, .48f, .57f, .23f); line(.43f, .16f, .57f, .23f) }
                "refresh" -> { line(.2f, .22f, .2f, .8f); line(.2f, .8f, .8f, .8f); line(.8f, .8f, .8f, .22f); line(.8f, .22f, .42f, .22f); line(.42f, .22f, .57f, .38f); line(.42f, .22f, .57f, .06f) }
                "close" -> { line(.22f, .22f, .78f, .78f); line(.22f, .78f, .78f, .22f) }
                "account" -> { Lines.circle(x + width * .5f, y + height * .7f, width * .18f); line(.2f, .12f, .2f, .32f); line(.2f, .32f, .5f, .45f); line(.5f, .45f, .8f, .32f); line(.8f, .32f, .8f, .12f); line(.2f, .12f, .8f, .12f) }
                "map" -> { line(.12f, .18f, .12f, .75f); line(.12f, .75f, .4f, .87f); line(.4f, .87f, .64f, .7f); line(.64f, .7f, .88f, .82f); line(.88f, .82f, .88f, .25f); line(.88f, .25f, .64f, .13f); line(.64f, .13f, .4f, .3f); line(.4f, .3f, .12f, .18f); line(.4f, .87f, .4f, .3f); line(.64f, .7f, .64f, .13f) }
                "schematic" -> { Lines.rect(x + width * .17f, y + height * .17f, width * .66f, height * .66f); line(.17f, .5f, .83f, .5f); line(.5f, .17f, .5f, .83f) }
                else -> {
                    val back = symbol == "back"
                    val start = if (back) .8f else .2f; val end = 1f - start
                    line(start, .5f, end, .5f)
                    line(end, .5f, if (back) .48f else .52f, .8f)
                    line(end, .5f, if (back) .48f else .52f, .2f)
                }
            }
            Lines.stroke(1f)
        }
    }
}
