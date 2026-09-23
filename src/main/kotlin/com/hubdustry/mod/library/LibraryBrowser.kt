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
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
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

    inner class LibraryDialog(private val kind: ContentKind) : BaseDialog("") {
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
        private val browserTable = Table()
        private val listing = ScrollPane(browserTable)
        private val search = TextField("")

        private fun wide() = Core.graphics.width / Scl.scl(1f) >= 900f
        private fun contentWidth() = (Core.graphics.width / Scl.scl(1f) - if (wide()) 294f else 32f).coerceIn(240f, 1680f)
        private fun caption(key: String) = Core.bundle.get("hubdustry.archive.$key")

        init {
            name = "library.browser.${kind.name}"
            listing.name = "library.scroll"
            listing.setScrollingDisabled(true, false)
            search.name = "library.search"
            search.setMessageText(if (kind == ContentKind.MAP) "@hubdustry.library.search-maps" else "@hubdustry.library.search-schematics")
            search.setMaxLength(Bounds.MAX_TEXT)
            search.changed {
                val next = search.text.take(Bounds.MAX_TEXT)
                if (next != query.text) {
                    query = query.copy(text = next, offset = 0)
                    listing.setScrollY(0f); load()
                }
            }
            LibraryTheme.controls(search); LibraryTheme.controls(listing)
            status.style = LibraryTheme.label(color = LibraryTheme.muted)
            pageLabel.style = LibraryTheme.label()
            background(LibraryTheme.fill(LibraryTheme.canvas))
            closeOnBack()
            shown {
                layoutChrome()
                val token = generation.incrementAndGet().also { generationAtOpen.set(it) }
                Core.app.post { if (isShown && token == generationAtOpen.get()) load() }
            }
            hidden {
                request?.cancel(); request = null
                catalogRequest?.cancel(); catalogRequest = null
                clearImages()
                sourceRequest?.cancel(); sourceRequest = null
                generationAtOpen.set(generation.incrementAndGet()); detailGeneration.incrementAndGet()
            }
            onResize { layoutChrome(); rebuild() }
        }

        private fun navigate(next: ContentKind) {
            if (next != kind) { hide(); dialog(next).show() }
        }

        private fun openFilters() {
            withCatalog { catalog ->
                LibraryFilters(query, catalog, account.session != null) { next ->
                    query = next; listing.setScrollY(0f); load()
                }.show()
            }
        }

        private fun layoutChrome() {
            clearChildren()
            val frame = Table()
            add(frame).grow()
            if (wide()) {
                frame.table { rail ->
                    rail.background(LibraryTheme.fill(ArchiveUi.bone)).top().left().margin(20f)
                    rail.add(ArchiveUi.text("HUB", 52f, ArchiveUi.black, true)).left().row()
                    rail.add(ArchiveUi.text("DUSTRY", 35f, ArchiveUi.black, true)).left().padTop(-12f).row()
                    rail.add(ArchiveUi.text("COMMUNITY / ARCHIVE", 11f, ArchiveUi.black)).left().padTop(12f).padBottom(32f).row()
                    rail.image(ArchiveUi.stripes()).height(8f).growX().padBottom(32f).row()
                    for (entry in ContentKind.values()) {
                        val label = (if (entry == ContentKind.SCHEMATIC) "01   " else "02   ") + caption(if (entry == ContentKind.SCHEMATIC) "schematics" else "maps")
                        rail.button(label, ArchiveUi.navigation(entry == kind)) { navigate(entry) }.growX().height(58f).padBottom(6f).get().name = "library.navigate.${entry.name}"
                        rail.row()
                    }
                    rail.add().growY().row()
                    rail.add(ArchiveUi.text("H / 01", 42f, Color.valueOf("a4a69e"), true)).left().padBottom(24f).row()
                    rail.button("@hubdustry.account", ArchiveUi.navigation(false)) { accountDialog() }.growX().height(48f).get().name = "library.account"
                    rail.row()
                    rail.button("@hubdustry.back", ArchiveUi.navigation(false)) { hide() }.growX().height(48f).padTop(6f)
                }.width(210f).growY()
                frame.image(LibraryTheme.fill(LibraryTheme.accent)).width(6f).growY()
            }
            frame.table { workspace ->
                workspace.top().margin(if (wide()) 32f else 12f)
                val available = contentWidth()
                workspace.table { masthead ->
                    masthead.left()
                    val trail = if (wide()) caption("community") else if (kind == ContentKind.MAP) "02" else "01"
                    masthead.add(ArchiveUi.text("HUBDUSTRY / " + trail, 12f, LibraryTheme.accent)).left().growX().minWidth(0f).ellipsis(true)
                    if (!wide()) {
                        val destination = if (kind == ContentKind.MAP) ContentKind.SCHEMATIC else ContentKind.MAP
                        masthead.button(ArchiveUi.icon(if (kind == ContentKind.MAP) "schematic" else "map"), LibraryTheme.icon()) { navigate(destination) }.size(40f).get().name = "library.navigate.${destination.name}"
                        masthead.button(ArchiveUi.icon("account"), LibraryTheme.icon()) { accountDialog() }.size(40f).get().name = "library.account"
                        masthead.button(ArchiveUi.icon("close"), LibraryTheme.icon()) { hide() }.size(40f)
                    }
                }.width(available).growX().padBottom(8f).row()
                workspace.table { heading ->
                    heading.left()
                    val headingSize = if (wide()) (available * .055f).coerceIn(36f, 66f) else 30f
                    heading.add(ArchiveUi.text(caption(if (kind == ContentKind.MAP) "map-title" else "schematic-title"), headingSize, bold = true)).left().growX().minWidth(0f).ellipsis(true)
                    if (wide()) {
                        heading.add(ArchiveUi.text(if (kind == ContentKind.MAP) "02" else "01", 70f, LibraryTheme.line, true)).right().padLeft(24f)
                    }
                }.width(available).padBottom(if (wide()) 18f else 8f).row()
                workspace.table { toolbar ->
                    toolbar.background(ArchiveUi.panel(LibraryTheme.paper)).margin(8f)
                    toolbar.image(ArchiveUi.icon("search")).size(24f).color(LibraryTheme.muted).padRight(10f)
                    toolbar.add(search).growX().minWidth(0f).height(40f)
                    toolbar.button(ArchiveUi.icon("filter"), LibraryTheme.icon()) { openFilters() }.size(44f).padLeft(8f).tooltip("@hubdustry.library.filters").get().name = "library.filter"
                    toolbar.button(ArchiveUi.icon("refresh"), LibraryTheme.icon()) { load() }.size(44f).get().name = "library.refresh"
                }.width(available).growX().padBottom(12f).row()
                workspace.table { controls ->
                    controls.left()
                    val sortCell = controls.button("", LibraryTheme.button()) { sortDialog() }.height(40f)
                    val sort = sortCell.get()
                    sort.update { sort.setText("@hubdustry.library.sort.${query.sort.name.lowercase()}") }
                    if (wide()) sortCell.width(210f) else { sortCell.colspan(4).growX().padBottom(6f); controls.row() }
                    controls.add(status).growX().minWidth(0f).left().padLeft(if (wide()) 18f else 0f)
                    controls.button(ArchiveUi.icon("back"), LibraryTheme.icon()) { if (page.offset > 0) { query = query.copy(offset = (page.offset - query.limit).coerceAtLeast(0)); listing.setScrollY(0f); load() } }.size(36f)
                    controls.add(pageLabel).pad(5f)
                    controls.button(ArchiveUi.icon("next"), LibraryTheme.icon()) { if (page.offset + page.items.size < page.total) { query = query.copy(offset = page.offset + query.limit); listing.setScrollY(0f); load() } }.size(36f)
                }.width(available).growX().padBottom(16f).row()
                workspace.add(listing).width(available).growY().row()
                if (wide()) workspace.table { footer ->
                    footer.add(ArchiveUi.text("HUBDUSTRY", 12f, LibraryTheme.muted)).left().growX()
                    footer.add(ArchiveUi.text(caption("footer"), 12f, LibraryTheme.muted)).right()
                }.width(available).padTop(10f)
            }.grow()
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
            browserTable.top().left()
            val available = contentWidth() - 8f
            status.setText(if (loading) "@loading" else Core.bundle.format("hubdustry.library.results", page.total))
            pageLabel.setText(if (page.total == 0) "0 / 0" else "${page.offset + 1}–${(page.offset + page.items.size).coerceAtMost(page.total)} / ${page.total}")
            if (loading) { browserTable.add("@loading", LibraryTheme.label()).pad(24f).center(); return }
            if (page.items.isEmpty()) { browserTable.add("@hubdustry.library.empty", LibraryTheme.label()).pad(24f).center(); return }
            val token = generationAtOpen.get()
            val featured = wide()
            if (featured) {
                browserTable.add(contentCard(page.items.first(), available, true, token)).width(available).height(320f).padBottom(24f).row()
            }
            val remaining = if (featured) page.items.drop(1) else page.items
            if (remaining.isEmpty()) return
            if (featured) {
                browserTable.table { divider ->
                    divider.add(ArchiveUi.text("// " + caption("more"), 13f, LibraryTheme.muted)).left()
                    divider.image(LibraryTheme.fill(LibraryTheme.line)).height(1f).growX().padLeft(18f)
                }.width(available).padBottom(14f).row()
            }
            val columns = (available / 290f).toInt().coerceAtLeast(1)
            val width = (available - (columns - 1) * 16f) / columns
            browserTable.table { grid ->
                grid.top().left()
                remaining.forEachIndexed { index, item ->
                    grid.add(contentCard(item, width, false, token)).width(width).height(if (wide()) 290f else 242f).padRight(if ((index + 1) % columns == 0) 0f else 16f).padBottom(16f)
                    if ((index + 1) % columns == 0) grid.row()
                }
            }.width(available).left()
        }

        private fun contentCard(item: LibraryItem, width: Float, feature: Boolean, token: Long): Button {
            val card = Button(ArchiveUi.card(feature)).apply { name = "library.card.${item.id}"; margin(0f); left() }
            card.clicked { detail(item) }
            val preview = Image(Tex.nomap).apply { setScaling(arc.util.Scaling.fit) }
            val imageWidth = if (feature) width * .55f else width - 24f
            card.add(preview).width(imageWidth).height(if (feature) 288f else if (wide()) 176f else 128f).pad(12f)
            if (!feature) card.row()
            card.table { labels ->
                labels.top().left()
                if (feature) {
                    labels.add(ArchiveUi.text("// " + caption("focus") + "  /  001", 12f, LibraryTheme.accent)).left().padBottom(16f).row()
                }
                val title = ArchiveUi.text(item.name.replace("[", "[["), if (feature) 34f else 23f, bold = true)
                labels.add(title).growX().minWidth(0f).left().ellipsis(true).row()
                item.attribution?.creditName?.let { labels.add(ArchiveUi.text(it.replace("[", "[["), 16f, LibraryTheme.muted)).ellipsis(true).growX().left().padTop(4f).row() }
                if (feature && item.description.isNotBlank()) labels.add(ArchiveUi.text(item.description.take(160).replace("[", "[["), 16f, LibraryTheme.muted)).wrap().growX().left().padTop(14f).padBottom(10f).row()
                labels.table { metadata ->
                    metadata.left()
                    metadata.add(ArchiveUi.text(Core.bundle.get("hubdustry.library.tier.${(item.rank?.tier ?: RankTier.NEW).name.lowercase()}"), 13f, LibraryTheme.accent)).padRight(12f)
                    item.rank?.let { metadata.add(ArchiveUi.text("${it.ratingCount} " + caption("ratings"), 13f, LibraryTheme.muted)) }
                    if (item.width != null && item.height != null) metadata.add(ArchiveUi.text("${item.width} × ${item.height}", 13f, LibraryTheme.muted)).padLeft(12f)
                }.left().padTop(8f).row()
                if (feature) {
                    labels.add().growY().row()
                    labels.table { action ->
                        action.background(ArchiveUi.panel(ArchiveUi.bone, ArchiveUi.bone, cut = true)).margin(12f)
                        action.add(ArchiveUi.text(caption("open"), 16f, ArchiveUi.black, true)).left().growX()
                        action.image(ArchiveUi.icon("next")).size(20f).color(ArchiveUi.black)
                    }.growX().height(44f).padTop(16f)
                }
            }.width(if (feature) width - imageWidth - 60f else width - 28f).growY().pad(if (feature) 18f else 12f)
            thumbnail(item, "card:${item.id}", if (feature) 512 else 256, { isShown && token == generationAtOpen.get() }) { texture ->
                preview.setDrawable(TextureRegionDrawable(arc.graphics.g2d.TextureRegion(texture)))
            }
            return card
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
            if (item.attribution?.identityVerified == true) { body.add("@hubdustry.library.author-verified").wrap().get().name = "library.label.accent"; body.row() }
            body.add("@hubdustry.library.kind." + item.kind.name.lowercase()).row()
            if (item.width != null && item.height != null) { body.add("${item.width} × ${item.height}").get().name = "library.label.secondary"; body.row() }
            item.rank?.let {
                body.add(Core.bundle.get("hubdustry.library.tier." + it.tier.name.lowercase()) + " · " + Core.bundle.format("hubdustry.library.reviews", it.ratingCount)).row()
            }
            body.add(item.description.ifBlank { Core.bundle.get("hubdustry.library.no-description") }.replace("[", "[[")).wrap().row()
            if (item.tags.isNotEmpty()) { body.add(item.tags.joinToString(" · ").replace("[", "[[")).wrap().get().name = "library.label.secondary"; body.row() }
            item.attribution?.licenseNotice?.let { body.add(it.replace("[", "[[")).wrap().get().name = "library.label.secondary"; body.row() }
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
