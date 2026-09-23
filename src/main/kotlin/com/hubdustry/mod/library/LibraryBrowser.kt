package com.hubdustry.mod.library

import arc.Core
import arc.graphics.Texture
import arc.graphics.PixmapIO
import arc.graphics.Pixmaps
import arc.graphics.Color
import arc.scene.ui.Button
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextField
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Image
import com.hubdustry.mod.auth.AccountClient
import mindustry.Vars
import mindustry.game.Schematics
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import mindustry.ui.dialogs.ModBrowserDialog
import mindustry.ui.FileChooser
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Native browser for the public library. Every response is generation-fenced. */
class LibraryBrowser {
    private val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "hubdustry-library").apply { isDaemon = true } }
    private val api = LibraryApi(executor = executor)
    private val account = AccountClient(executor = executor)
    private val views = mutableMapOf<ContentKind, LibraryDialog>()

    fun install() {
        if (Vars.ui == null) return
        Vars.ui.settings.addCategory("Hubdustry", Icon.book) { table ->
            table.button("@hubdustry.library.schematics", Icon.book) { dialog(ContentKind.SCHEMATIC).show() }.size(280f, 54f)
            table.row()
            table.button("@hubdustry.library.maps", Icon.map) { dialog(ContentKind.MAP).show() }.size(280f, 54f)
        }
    }

    fun dialog(): LibraryDialog = dialog(ContentKind.SCHEMATIC)
    fun dialog(kind: ContentKind): LibraryDialog = views.getOrPut(kind) { LibraryDialog(kind) }

    inner class LibraryDialog(private val kind: ContentKind) : ModBrowserDialog() {
        private val generation = AtomicLong()
        private var query = LibraryQuery(kind = kind)
        private var page = LibraryPage(emptyList(), 0, 0, 24)
        private var loading = false
        private var request: RequestHandle? = null
        private var sourceRequest: RequestHandle? = null
        private var catalogRequest: RequestHandle? = null
        private val imageRequests = LinkedHashMap<String, RequestHandle>()
        private val imageEpoch = AtomicLong()
        private val status = arc.scene.ui.Label("")
        private val generationAtOpen = AtomicLong()
        private val detailGeneration = AtomicLong()
        private val pageLabel = arc.scene.ui.Label("")
        private val textures = LinkedHashMap<String, Texture>()
        private val listing: ScrollPane
        private val header: Table

        init {
            name = "library.browser.${kind.name}"
            title.setText(if (kind == ContentKind.MAP) "@hubdustry.library.maps" else "@hubdustry.library.schematics")
            header = cont.children.get(0) as Table
            listing = cont.children.get(1) as ScrollPane
            listing.name = "library.scroll"
            val zoom = header.children.get(0)
            val search = header.children.get(1) as TextField
            search.name = "library.search"
            search.setMessageText(if (kind == ContentKind.MAP) "@hubdustry.library.search-maps" else "@hubdustry.library.search-schematics")
            search.setMaxLength(Bounds.MAX_TEXT)
            zoom.setColor(LibraryTheme.ink)
            val tools = Table()
            tools.button(Icon.filter, Styles.emptyi) {
                withCatalog { catalog ->
                    LibraryFilters(query, catalog, account.session != null) { next ->
                        query = next; listing.setScrollY(0f); load()
                    }.show()
                }
            }.size(44f).tooltip("@hubdustry.library.filters").get().name = "library.filter"
            tools.button(Icon.refresh, Styles.emptyi) { load() }.size(44f).tooltip("@refresh").get().name = "library.refresh"
            tools.button(Icon.settings, Styles.emptyi) { accountDialog() }.size(44f).tooltip("@hubdustry.account").get().name = "library.account"
            fun layoutHeader() {
                header.clear()
                header.background(LibraryTheme.fill(LibraryTheme.paper)).margin(10f)
                header.add(zoom).padRight(8f)
                header.add(search).growX().minWidth(0f)
                if (LibraryTheme.width() < 600f) {
                    header.row()
                    header.add(tools).colspan(2).right().padTop(6f)
                } else header.add(tools)
            }
            layoutHeader()
            shown {
                val token = generation.incrementAndGet().also { generationAtOpen.set(it) }
                // Arc fires shown before attaching the dialog to its scene.
                Core.app.post { if (isShown && token == generationAtOpen.get()) load() }
            }
            hidden {
                request?.cancel(); request = null
                catalogRequest?.cancel(); catalogRequest = null
                clearImages()
                sourceRequest?.cancel(); sourceRequest = null
                generationAtOpen.set(generation.incrementAndGet()); detailGeneration.incrementAndGet()
            }
            cont.clear()
            // Keep the native overlay footer outside the scrolling viewport.
            cont.top().marginBottom(84f).add(header).growX().padTop(14f).row()
            val bar = Table()
            val sort = bar.button("", Styles.defaultt, Runnable { sortDialog() }).get()
            sort.update { sort.setText("@hubdustry.library.sort.${query.sort.name.lowercase()}") }
            val navigation = Table()
            navigation.button(Icon.left, Styles.emptyi, Runnable { if (page.offset > 0) { query = query.copy(offset = (page.offset - query.limit).coerceAtLeast(0)); listing.setScrollY(0f); load() } }).size(40f)
            navigation.add(pageLabel).pad(6f)
            navigation.button(Icon.right, Styles.emptyi, Runnable { if (page.offset + page.items.size < page.total) { query = query.copy(offset = page.offset + query.limit); listing.setScrollY(0f); load() } }).size(40f)
            fun layoutBar() {
                bar.clear()
                if (LibraryTheme.width() < 600f) {
                    bar.add(sort).colspan(2).growX().height(44f).padBottom(6f).row()
                } else bar.add(sort).width(200f).height(44f).padRight(10f)
                bar.add(status).growX().minWidth(0f).left()
                bar.add(navigation).right()
            }
            layoutBar()
            onResize { layoutHeader(); layoutBar() }
            cont.add(bar).growX().padTop(10f).padBottom(8f).row()
            cont.add(listing).grow()
            LibraryTheme.dialog(this, if (kind == ContentKind.MAP) "HUBDUSTRY / LIBRARY 02" else "HUBDUSTRY / LIBRARY 01")
        }

        override fun rebuildBrowser() {
            if (!isShown || Core.scene.dialog != this) return
            if (query.text != searchtxt) {
                query = query.copy(text = searchtxt.take(Bounds.MAX_TEXT), offset = 0)
                listing.setScrollY(0f); load()
            } else rebuild()
        }

        private fun load() {
            if (!isShown) return
            val scroll = listing.scrollY
            sourceRequest?.cancel(); sourceRequest = null
            request?.cancel(); loading = true; rebuild()
            val token = generation.incrementAndGet().also { generationAtOpen.set(it) }
            request = api.list(account.session?.accessToken, query) { result ->
                Core.app.post {
                    if (!isShown || token != generationAtOpen.get()) return@post
                    request = null; loading = false
                    if (result.value != null) page = result.value
                    rebuild()
                    // The loading placeholder temporarily shrinks the scroll range.
                    listing.validate(); listing.setScrollY(scroll); listing.updateVisualScroll()
                    if (result.error != null) status.setText("@hubdustry.library.unavailable")
                }
            }
        }

        private fun rebuild() {
            clearImages()
            browserTable.clearChildren()
            browserTable.top().left().margin(10f)
            val available = (Core.graphics.width / Scl.scl(1f) - 52f).coerceIn(240f, 1280f)
            cont.cells.forEach { it.maxWidth(available) }
            cont.getCell(header).width(available)
            cont.getCell(listing).width(available)
            status.setText(if (loading) "@loading" else Core.bundle.format("hubdustry.library.results", page.total))
            pageLabel.setText(if (page.total == 0) "0 / 0" else "${page.offset + 1}–${(page.offset + page.items.size).coerceAtMost(page.total)} / ${page.total}")
            if (loading) { browserTable.add("@loading", LibraryTheme.label()).pad(24f).center(); return }
            if (page.items.isEmpty()) { browserTable.add("@hubdustry.library.empty", LibraryTheme.label()).pad(24f).center(); return }
            val cardWidth = if (kind == ContentKind.MAP) 310f else 250f
            val columns = (available / (cardWidth + 12f)).toInt().coerceAtLeast(1)
            val width = (available / columns - 12f).coerceAtMost(cardWidth)
            val previewHeight = if (available < 600f) 128f else if (kind == ContentKind.MAP) 190f else width - 16f
            val token = generationAtOpen.get()
            page.items.forEachIndexed { index, item ->
                val card = Button(LibraryTheme.card()).apply { name = "library.card.${item.id}"; margin(0f); left() }
                card.clicked { detail(item) }
                val preview = Image(Tex.nomap)
                preview.setScaling(arc.util.Scaling.fit)
                card.add(preview).width(width - 16f).height(previewHeight).pad(8f).row()
                card.table { labels ->
                    labels.top().left()
                    labels.add(item.name.replace("[", "[["), LibraryTheme.label(true, text = item.name)).fontScale(.8f).growX().ellipsis(true).left().row()
                    item.attribution?.creditName?.let { labels.add(it.replace("[", "[["), LibraryTheme.label(color = LibraryTheme.muted, text = it)).fontScale(.85f).ellipsis(true).growX().left().row() }
                    labels.table { ratings ->
                        ratings.left()
                        for (star in 1..5) ratings.image(TextureRegionDrawable(Icon.star.region)).size(12f).color(if (star <= (item.rank?.score ?: 0.0)) LibraryTheme.ink else LibraryTheme.line).padRight(2f)
                        ratings.add("(${item.rank?.ratingCount ?: 0})", LibraryTheme.label(color = LibraryTheme.muted)).fontScale(.75f).padLeft(3f)
                    }.left().row()
                    labels.add("@hubdustry.library.tier.${(item.rank?.tier ?: RankTier.NEW).name.lowercase()}", LibraryTheme.label(color = LibraryTheme.muted)).fontScale(.8f).left()
                    if (kind == ContentKind.MAP && item.width != null && item.height != null) {
                        labels.row(); labels.add("${item.width} × ${item.height}", LibraryTheme.label(color = LibraryTheme.muted)).fontScale(.8f).left()
                    }
                }.width(width - 16f).growY().pad(6f)
                browserTable.add(card).width(width).height(previewHeight + if (kind == ContentKind.MAP) 120f else 116f).pad(6f).left()
                if ((index + 1) % columns == 0) browserTable.row()
                thumbnail(item, "card:${item.id}", 256, { isShown && token == generationAtOpen.get() }) { texture ->
                    preview.setDrawable(TextureRegionDrawable(arc.graphics.g2d.TextureRegion(texture)))
                }
            }
        }

        private fun clearImages() {
            imageEpoch.incrementAndGet()
            imageRequests.values.forEach { it.cancel() }; imageRequests.clear()
            textures.values.forEach { it.dispose() }; textures.clear()
        }

        private fun sortDialog() {
            val choices = BaseDialog("@hubdustry.library.sort")
            choices.addCloseButton()
            LibrarySort.values().forEach { sort ->
                choices.cont.button("@hubdustry.library.sort.${sort.name.lowercase()}", Styles.squareTogglet, Runnable {
                    query = query.copy(sort = sort, offset = 0); choices.hide(); listing.setScrollY(0f); load()
                }).width(280f).height(48f).pad(4f).checked(query.sort == sort).apply { get().name = "library.sort.choice.${sort.name}" }.row()
            }
            LibraryTheme.dialog(choices, "HUBDUSTRY / SORT")
            choices.show()
        }

        private fun withCatalog(action: (TagCatalog) -> Unit) {
            val token = generationAtOpen.get()
            catalogRequest?.cancel()
            catalogRequest = api.tags(kind) { result -> Core.app.post {
                if (!isShown || token != generationAtOpen.get()) return@post
                catalogRequest = null
                if (result.value == null) Vars.ui.showErrorMessage("@hubdustry.library.tags-unavailable")
                else action(result.value)
            } }
        }

        private fun selectTags(selected: List<String>, done: (List<String>) -> Unit) {
            withCatalog { catalog ->
                val allowed = catalog.categories.flatMap { it.tags }.map { it.id }.toSet()
                LibraryFilters(query.copy(tags = selected.filter { it in allowed }), catalog, false, true) { next -> done(next.tags) }.show()
            }
        }

        private fun thumbnail(item: LibraryItem, key: String, maxSide: Int, current: () -> Boolean, ready: (Texture) -> Unit) {
            if (item.artifactId == null) return
            textures[key]?.let { ready(it); return }
            imageRequests.remove(key)?.cancel()
            val epoch = imageEpoch.get()
            imageRequests[key] = api.image(account.session?.accessToken, item) { result ->
                val pixmap = result.value?.let { bytes -> runCatching {
                    val decoded = PixmapIO.readPNG(SourceVerifier.verifyThumbnail(bytes))
                    if (decoded.width <= maxSide && decoded.height <= maxSide) decoded else {
                        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
                        try { Pixmaps.scale(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) }
                        finally { decoded.dispose() }
                    }
                }.getOrNull() } ?: return@image
                Core.app.post {
                    try {
                        if (epoch != imageEpoch.get() || !current()) return@post
                        imageRequests.remove(key)
                        val texture = Texture(pixmap)
                        textures.put(key, texture)?.dispose()
                        ready(texture)
                    } finally { pixmap.dispose() }
                }
            }
        }

        private fun detail(item: LibraryItem) {
            val detail = BaseDialog(item.name.replace("[", "[["))
            detail.name = "library.detail"
            detail.setFillParent(true)
            detail.addCloseButton()
            val width = (Core.graphics.width / Scl.scl(1f) - 48f).coerceIn(240f, 680f)
            val body = Table().apply { top().left(); defaults().pad(6f).growX().left() }
            val preview = Image(Tex.nomap).apply { name = "library.detail.preview"; setScaling(arc.util.Scaling.fit) }
            body.add(preview).height((Core.graphics.height / Scl.scl(1f) * .42f).coerceIn(180f, 400f)).row()
            item.attribution?.creditName?.let { body.add(Core.bundle.get("hubdustry.library.author") + ": " + it.replace("[", "[[")).wrap().row() }
            if (item.attribution?.identityVerified == true) body.add("@hubdustry.library.author-verified").color(Pal.accent).wrap().row()
            body.add("@hubdustry.library.kind." + item.kind.name.lowercase()).row()
            if (item.width != null && item.height != null) body.add("${item.width} × ${item.height}").color(Color.lightGray).row()
            item.rank?.let {
                body.add(Core.bundle.get("hubdustry.library.tier." + it.tier.name.lowercase()) + " · " + Core.bundle.format("hubdustry.library.reviews", it.ratingCount)).row()
            }
            body.add(item.description.ifBlank { Core.bundle.get("hubdustry.library.no-description") }.replace("[", "[[")).wrap().row()
            if (item.tags.isNotEmpty()) body.add(item.tags.joinToString(" · ").replace("[", "[[")).color(Color.lightGray).wrap().row()
            item.attribution?.licenseNotice?.let { body.add(it.replace("[", "[[")).wrap().color(Color.lightGray).row() }
            if (item.capabilities.canRate && account.session != null) {
                body.add("@hubdustry.library.rate").padTop(14f).row()
                body.table { ratings ->
                    (1..5).forEach { score -> ratings.button(score.toString(), Styles.squareTogglet, Runnable { account.session?.accessToken?.let { token -> api.rate(token, item, score) { result -> actionResult(detail, result) } } }).size(44f) }
                }.row()
            }
            if (item.capabilities.canEdit && account.session != null) body.button("@hubdustry.library.edit", Styles.defaultt, Runnable { edit(item, detail) }).height(48f).row()
            if (item.capabilities.canSubmit && account.session != null) body.button("@hubdustry.library.publish", Styles.defaultt, Runnable { account.session?.accessToken?.let { token -> api.submit(token, item) { result -> actionResult(detail, result) } } }).height(48f).row()
            if (item.capabilities.canModerate && account.session != null) {
                if (item.state == LibraryState.PUBLISHED) body.button("@hubdustry.library.moderate", Styles.defaultt, Runnable { moderation(item, "HIDE", detail) }).height(48f).row()
                if (item.state == LibraryState.HIDDEN) body.button("@hubdustry.library.restore", Styles.defaultt, Runnable { moderation(item, "RESTORE", detail) }).height(48f).row()
            }
            detail.cont.pane(body).width(width).growY().scrollX(false)
            detail.buttons.button("@hubdustry.library.import", Icon.download) { detail.hide(); download(item) }.size(190f, 64f).get().name = "library.import"
            val detailToken = detailGeneration.incrementAndGet()
            val key = "detail:" + item.id
            detail.hidden {
                if (detailToken == detailGeneration.get()) {
                    detailGeneration.incrementAndGet()
                    imageRequests.remove(key)?.cancel()
                    textures.remove(key)?.dispose()
                }
            }
            LibraryTheme.dialog(detail, "HUBDUSTRY / CONTENT")
            detail.show()
            thumbnail(item, key, 512, { detail.isShown && detailToken == detailGeneration.get() }) { texture ->
                preview.setDrawable(TextureRegionDrawable(arc.graphics.g2d.TextureRegion(texture)))
                preview.name = "library.detail.preview.ready"
            }
        }

        private fun accountDialog() {
            val accountView = BaseDialog("@hubdustry.account")
            accountView.name = "library.account-panel"
            accountView.addCloseButton()
            accountView.cont.defaults().width(300f).height(52f).pad(6f)
            accountView.cont.add(account.session?.displayName?.replace("[", "[[") ?: Core.bundle.get("hubdustry.account.signed-out")).wrap().row()
            if (account.session == null) {
                accountView.cont.button("@hubdustry.account.sign-in") { accountView.hide(); accountButton() }.row()
            } else {
                accountView.cont.button("@hubdustry.library.mine", Icon.book) { accountView.hide(); query = query.copy(ownerMe = true, state = null, offset = 0); load() }.row()
                accountView.cont.button("@hubdustry.library.upload", Icon.upload) { accountView.hide(); upload() }.row()
                accountView.cont.button("@hubdustry.account.refresh", Icon.refresh) { accountView.hide(); accountButton() }.row()
                accountView.cont.button("@hubdustry.account.logout") { account.logout(); accountView.hide(); query = query.copy(ownerMe = false, state = null, offset = 0); load() }.row()
            }
            LibraryTheme.dialog(accountView, "HUBDUSTRY / ACCOUNT")
            accountView.show()
        }

        private fun download(item: LibraryItem) {
            status.setText("@hubdustry.library.downloading")
            val token = generationAtOpen.get()
            sourceRequest?.cancel()
            sourceRequest = api.source(account.session?.accessToken, item) { result ->
                val verified = result.value?.let { bytes -> runCatching { SourceVerifier.verify(bytes, item.sha256, item.sizeBytes) }.getOrNull() }
                Core.app.post {
                if (!isShown || token != generationAtOpen.get()) return@post
                sourceRequest = null
                if (result.value == null) { status.setText("@hubdustry.library.download-failed"); return@post }
                if (verified == null) { status.setText("@hubdustry.library.invalid-source"); return@post }
                try {
                    // Native import uses shared game serializers and creates GL previews.
                    when (item.kind) {
                        ContentKind.SCHEMATIC -> Vars.schematics.add(Schematics.read(ByteArrayInputStream(verified)))
                        ContentKind.MAP -> importMap(item, verified)
                    }
                    status.setText("@hubdustry.library.imported")
                } catch (_: Exception) { status.setText("@hubdustry.library.invalid-source") }
                }
            }
        }

        private fun importMap(item: LibraryItem, bytes: ByteArray) {
            val file = Vars.customMapDirectory.child("hubdustry-${item.id}.msav")
            if (file.exists()) throw IllegalStateException("already-imported")
            val temp = Vars.tmpDirectory.child("hubdustry-${item.id}")
            temp.writeBytes(bytes)
            try { Vars.maps.importMap(temp); temp.delete() } catch (e: Exception) { temp.delete(); throw e }
        }

        private fun upload() {
            val token = account.session?.accessToken ?: run { status.setText("@hubdustry.account.required"); return }
            FileChooser.open(if (kind == ContentKind.SCHEMATIC) "msch" else "msav").submit { file ->
                if (file.length() !in 1..Bounds.MAX_SOURCE_BYTES.toLong()) { status.setText("@hubdustry.library.upload-failed"); return@submit }
                status.setText("@hubdustry.library.uploading")
                executor.submit { runCatching { file.read().use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= Bounds.MAX_SOURCE_BYTES); output.write(buffer, 0, count) }
                    output.toByteArray()
                } }.onSuccess { bytes -> Core.app.post {
                    api.upload(token, kind, bytes) { receipt -> Core.app.post {
                        val receiptValue = receipt.value
                        if (receiptValue == null) { status.setText("@hubdustry.library.upload-failed") } else Vars.ui.showTextInput("@hubdustry.library.name", "@hubdustry.library.name", 160, file.nameWithoutExtension()) { name ->
                            Vars.ui.showTextInput("@hubdustry.library.description", "@hubdustry.library.description", Bounds.MAX_DESCRIPTION, "") { description ->
                                selectTags(emptyList()) { tags ->
                                    runCatching { api.create(token, receiptValue.assetId, receiptValue.previewRequestId, name, description, tags) { result -> Core.app.post { if (result.error == null) { query = query.copy(ownerMe = true, state = null, offset = 0); load() } else Vars.ui.showErrorMessage("@hubdustry.library.action-failed") } } }.onFailure { Vars.ui.showErrorMessage("@hubdustry.library.action-failed") }
                                }
                            }
                        }
                    } }
                } }.onFailure { Core.app.post { status.setText("@hubdustry.library.upload-failed") } } }
            }
        }

        private fun edit(item: LibraryItem, detail: BaseDialog) {
            val token = account.session?.accessToken ?: return
            Vars.ui.showTextInput("@hubdustry.library.edit", "@hubdustry.library.name", 160, item.name) { name ->
                Vars.ui.showTextInput("@hubdustry.library.description", "@hubdustry.library.description", Bounds.MAX_DESCRIPTION, item.description) { description ->
                    selectTags(item.tags) { tags ->
                        runCatching { api.edit(token, item, name, description, tags) { result -> actionResult(detail, result) } }.onFailure { Vars.ui.showErrorMessage("@hubdustry.library.action-failed") }
                    }
                }
            }
        }

        private fun moderation(item: LibraryItem, decision: String, detail: BaseDialog) {
            val token = account.session?.accessToken ?: return
            Vars.ui.showTextInput("@hubdustry.library.reason", "@hubdustry.library.reason", 2000, "") { reason ->
                if (reason.isBlank()) { Vars.ui.showErrorMessage("@hubdustry.library.reason-required") } else
                Vars.ui.showConfirm("@hubdustry.library.moderate", "@hubdustry.library.reason.confirm", Runnable { api.moderate(token, item, decision, reason.trim()) { result -> actionResult(detail, result) } })
            }
        }

        private fun actionResult(detail: BaseDialog, result: ApiResponse<LibraryItem>) {
            Core.app.post {
                if (!detail.isShown) return@post
                if (result.error == null) { detail.hide(); load() }
                else Vars.ui.showErrorMessage("@hubdustry.library.action-failed")
            }
        }

        private fun accountButton() {
            val current = account.session
            if (current != null) {
                account.refresh { result -> Core.app.post { status.setText(if (result.isSuccess) "@hubdustry.account.refreshed" else "@hubdustry.account.failed") } }
                return
            }
            status.setText("@hubdustry.account.waiting")
            account.pair { result ->
                result.onSuccess { pairing -> account.collect(pairing) { collected -> Core.app.post { status.setText(if (collected.isSuccess) "@hubdustry.account.signed-in" else "@hubdustry.account.failed"); load() } } }
                    .onFailure { Core.app.post { status.setText("@hubdustry.account.failed") } }
            }
        }
    }
}
