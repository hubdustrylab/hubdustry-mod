package com.hubdustry.mod.library

import arc.Core
import arc.graphics.Texture
import arc.graphics.PixmapIO
import arc.scene.ui.Image
import com.hubdustry.mod.auth.AccountClient
import mindustry.Vars
import mindustry.game.Schematics
import mindustry.gen.Icon
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
    private val generation = AtomicLong()
    private var view: LibraryDialog? = null

    fun install() {
        if (Vars.ui == null) return
        Vars.ui.settings.addCategory("Hubdustry", Icon.book) { table ->
            table.button("@hubdustry.library", Icon.book) { dialog().show() }.size(230f, 54f)
        }
    }

    fun dialog(): LibraryDialog = view ?: LibraryDialog().also { view = it }

    inner class LibraryDialog : BaseDialog("@hubdustry.library") {
        private var query = LibraryQuery()
        private var page = LibraryPage(emptyList(), 0, 0, 24)
        private var search = ""
        private var tags = ""
        private var loading = false
        private var request: RequestHandle? = null
        private val thumbnails = ThumbnailCache()
        private val content = arc.scene.ui.layout.Table()
        private val status = arc.scene.ui.Label("")
        private val generationAtOpen = AtomicLong()
        private val detailGeneration = AtomicLong()
        private val pageLabel = arc.scene.ui.Label("")
        private val textures = LinkedHashMap<String, Texture>()

        init {
            setFillParent(true)
            addCloseListener()
            shown { generationAtOpen.set(generation.incrementAndGet()); rebuild(); load() }
            hidden { request?.cancel(); request = null; thumbnails.clear(); textures.values.forEach { it.dispose() }; textures.clear(); generation.incrementAndGet(); detailGeneration.incrementAndGet() }
            cont.top().left().defaults().pad(4f)
            cont.table { bar ->
                bar.button("@schematic", Styles.defaultt, Runnable { query = query.copy(kind = ContentKind.SCHEMATIC, offset = 0); load() })
                bar.button("@map", Styles.defaultt, Runnable { query = query.copy(kind = ContentKind.MAP, offset = 0); load() })
                bar.add(status).growX().left()
                bar.button("@hubdustry.account", Styles.defaultt, Runnable { accountButton() })
                bar.button("@hubdustry.account.logout", Styles.defaultt, Runnable { account.logout(); status.setText("@hubdustry.account.signed-out"); load() })
                bar.button("@hubdustry.library.upload", Styles.defaultt, Runnable { upload() })
            }.growX().row()
            cont.table { bar ->
                val field = bar.field(search) { value -> search = value.take(Bounds.MAX_TEXT); query = query.copy(text = search, offset = 0); load() }.growX().get()
                field.setMessageText("@search")
                val tagField = bar.field(tags) { value -> tags = value.take(Bounds.MAX_TAG * Bounds.MAX_TAGS); query = query.copy(tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(Bounds.MAX_TAGS), offset = 0); load() }.width(180f).get()
                tagField.setMessageText("@hubdustry.library.tags")
                bar.button(Icon.refresh, Styles.emptyi) { load() }
                bar.button("@hubdustry.library.sort", Styles.defaultt, Runnable { query = query.copy(sort = LibrarySort.values()[(query.sort.ordinal + 1) % LibrarySort.values().size], offset = 0); load() })
                bar.button("@hubdustry.library.mine", Styles.defaultt, Runnable { query = query.copy(ownerMe = !query.ownerMe, offset = 0); load() })
                bar.button("@hubdustry.library.rank", Styles.defaultt, Runnable { query = query.copy(minRank = if (query.minRank == null) RankTier.QUALITY else null, offset = 0); load() })
            }.growX().row()
            cont.add(content).grow().row()
            cont.table { bar ->
                bar.button(Icon.left, Styles.emptyi, Runnable { if (page.offset > 0) { query = query.copy(offset = (page.offset - query.limit).coerceAtLeast(0)); load() } })
                bar.add(pageLabel).pad(8f)
                bar.button(Icon.right, Styles.emptyi, Runnable { if (page.offset + page.items.size < page.total) { query = query.copy(offset = page.offset + query.limit); load() } })
            }.row()
            buttons.button("@back", Icon.left, this::hide).size(180f, 54f)
        }

        private fun load() {
            if (!isShown) return
            request?.cancel(); loading = true; rebuild()
            val token = generation.incrementAndGet().also { generationAtOpen.set(it) }
            request = api.list(account.session?.accessToken, query) { result ->
                Core.app.post {
                    if (!isShown || token != generationAtOpen.get()) return@post
                    request = null; loading = false
                    if (result.value != null) page = result.value
                    rebuild()
                    if (result.error != null) status.setText("@hubdustry.library.unavailable")
                }
            }
        }

        private fun rebuild() {
            content.clearChildren()
            status.setText(if (loading) "@loading" else "${page.total} items")
            pageLabel.setText(if (page.total == 0) "0 / 0" else "${page.offset + 1}–${(page.offset + page.items.size).coerceAtMost(page.total)} / ${page.total}")
            if (loading) { content.add("@loading").center(); return }
            if (page.items.isEmpty()) { content.add("@hubdustry.library.empty").center(); return }
            content.pane { grid ->
                grid.top().left()
                page.items.forEachIndexed { index, item ->
                    grid.button("${item.name}\n${item.rank?.tier ?: "NEW"} · ${item.rank?.score?.let { "%.1f".format(it) } ?: "new"}", Styles.grayt) { detail(item) }.width(260f).height(58f)
                    if ((index + 1) % 3 == 0) grid.row()
                }
            }.grow().scrollY(true)
        }

        private fun detail(item: LibraryItem) {
            val detail = BaseDialog(item.name)
            detail.cont.top().left().defaults().pad(5f)
            detail.cont.add(item.description.ifBlank { "@hubdustry.library.no-description" }).growX().wrap().row()
            detail.cont.add("${item.kind} · ${item.width ?: "?"} × ${item.height ?: "?"}").row()
            detail.cont.add("${item.sizeBytes} bytes · ${item.state}").row()
            detail.cont.add("${item.tags.joinToString(", ")} · ${item.updatedAt ?: item.createdAt ?: ""}").row()
            item.rank?.let { detail.cont.add("${it.tier} · ${it.score ?: "new"} · ${it.ratingCount} ratings").row() }
            item.attribution?.creditName?.let { detail.cont.add(Core.bundle.get("author") + ": " + it.replace("[", "[[")).row() }
            item.attribution?.let { attribution ->
                if (attribution.identityVerified) detail.cont.add("@hubdustry.library.author-verified").row()
                attribution.licenseNotice?.let { notice -> detail.cont.pane { pane -> pane.add(notice.replace("[", "[[")).wrap().width(420f) }.maxHeight(100f).row() }
            }
            if (item.capabilities.canRate && account.session != null) {
                detail.cont.table { ratings ->
                    (1..5).forEach { score -> ratings.button(score.toString(), Styles.defaultt, Runnable { account.session?.accessToken?.let { token -> api.rate(token, item, score) { result -> actionResult(detail, result) } } }) }
                }.row()
            }
            if (item.capabilities.canSubmit && account.session != null) {
                detail.cont.button("@hubdustry.library.publish", Styles.defaultt, Runnable { account.session?.accessToken?.let { token -> api.submit(token, item) { result -> actionResult(detail, result) } } }).row()
            }
            if (item.capabilities.canModerate && account.session != null) {
                if (item.state == LibraryState.PUBLISHED) detail.cont.button("@hubdustry.library.moderate", Styles.defaultt, Runnable { moderation(item, "HIDE", detail) }).row()
                if (item.state == LibraryState.HIDDEN) detail.cont.button("@hubdustry.library.restore", Styles.defaultt, Runnable { moderation(item, "RESTORE", detail) }).row()
            }
            if (item.capabilities.canEdit && account.session != null) detail.cont.button("@hubdustry.library.edit", Styles.defaultt, Runnable { edit(item, detail) }).row()
            detail.buttons.button("@hubdustry.library.import", Icon.download) { detail.hide(); download(item) }.size(190f, 54f)
            detail.buttons.button("@back", Icon.left) { detail.hide() }.size(150f, 54f)
            detail.show()
            val detailToken = detailGeneration.incrementAndGet()
            detail.hidden { if (detailToken == detailGeneration.get()) textures.remove(item.id)?.dispose() }
            if (item.artifactId != null) {
                api.image(account.session?.accessToken, item) { result ->
                    result.value?.let { bytes -> Core.app.post {
                        if (!detail.isShown || detailToken != detailGeneration.get()) return@post
                        runCatching {
                            val png = SourceVerifier.verifyThumbnail(bytes)
                            thumbnails.put(item.id, png)
                            val pixmap = PixmapIO.readPNG(png)
                            require(pixmap.width <= 2048 && pixmap.height <= 2048) { "thumbnail dimensions out of bounds" }
                            val texture = try { Texture(pixmap) } finally { pixmap.dispose() }; textures.put(item.id, texture)?.dispose()
                            detail.cont.add(Image(texture)).size(180f).row()
                        }
                    } }
                }
            }
        }

        private fun download(item: LibraryItem) {
            status.setText("@hubdustry.library.downloading")
            val token = generationAtOpen.get()
            api.source(account.session?.accessToken, item) { result -> Core.app.post {
                if (!isShown || token != generationAtOpen.get()) return@post
                val bytes = result.value
                if (bytes == null) { status.setText("@hubdustry.library.download-failed"); return@post }
                try {
                    val verified = SourceVerifier.verify(bytes, item.sha256, item.sizeBytes)
                    when (item.kind) {
                        ContentKind.SCHEMATIC -> Vars.schematics.add(Schematics.read(ByteArrayInputStream(verified)))
                        ContentKind.MAP -> importMap(item, verified)
                    }
                    status.setText("@hubdustry.library.imported")
                } catch (_: Exception) { status.setText("@hubdustry.library.invalid-source") }
            } }
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
            val kind = query.kind ?: ContentKind.SCHEMATIC
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
                                Vars.ui.showTextInput("@hubdustry.library.tags", "@hubdustry.library.tags", Bounds.MAX_TAG * Bounds.MAX_TAGS, "") { rawTags ->
                                    val tags = rawTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(Bounds.MAX_TAGS)
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
                    Vars.ui.showTextInput("@hubdustry.library.tags", "@hubdustry.library.tags", Bounds.MAX_TAG * Bounds.MAX_TAGS, item.tags.joinToString(",")) { rawTags ->
                        val tags = rawTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(Bounds.MAX_TAGS)
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
