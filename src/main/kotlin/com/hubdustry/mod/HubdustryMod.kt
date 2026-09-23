package com.hubdustry.mod

import arc.Events
import arc.scene.ui.Dialog
import mindustry.mod.Mod
import mindustry.game.EventType.ClientLoadEvent
import com.hubdustry.mod.library.LibraryBrowser

/** Public Kotlin content client. Network work is opt-in and independent of vanilla startup. */
class HubdustryMod : Mod() {
    private var browser: LibraryBrowser? = null

    override fun init() {
        Events.on(ClientLoadEvent::class.java) {
            browser = LibraryBrowser().also { it.install() }
        }
    }

    fun showLibrary(): Dialog? = browser?.dialog()
}
