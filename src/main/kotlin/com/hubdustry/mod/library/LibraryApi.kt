package com.hubdustry.mod.library

import arc.util.serialization.Jval
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

data class ApiResponse<T>(val value: T? = null, val status: Int = 0, val error: String? = null)

class RequestHandle internal constructor(private val future: Future<*>, private val connection: AtomicReference<HttpURLConnection?>) {
    fun cancel() { connection.get()?.disconnect(); future.cancel(true) }
}

/** Small, cancellable HTTP adapter. Tokens are held only in memory and never logged. */
class LibraryApi(
    private val origin: String = "https://api.hubdustry.com",
    private val executor: ExecutorService
) {
    private companion object { const val MAX_JSON_BYTES = 6 * 1024 * 1024; const val MAX_SOURCE_BYTES = 3_000_000; const val MAX_IMAGE_BYTES = 2 * 1024 * 1024 }
    private val base = origin.trimEnd('/') + "/v1/library"

    fun capabilities(token: String?, done: (ApiResponse<LibraryCapabilities>) -> Unit): RequestHandle =
        request("GET", "/capabilities", token, null, done, ::parseCapabilities)

    fun list(token: String?, query: LibraryQuery, done: (ApiResponse<LibraryPage>) -> Unit): RequestHandle =
        request("GET", "/items?${LibraryQueryEncoder.encode(query)}", token, null, done, ::parsePage)

    fun tags(kind: ContentKind, done: (ApiResponse<TagCatalog>) -> Unit): RequestHandle =
        request("GET", "/tags?kind=${kind.name}", null, null, done, { raw ->
            val root = Jval.read(raw)
            require(ContentKind.valueOf(root.getString("kind")) == kind)
            val categories = root.get("categories").asArray().toList().map { category ->
                TagCategory(category.getString("id"), category.getString("label"), category.get("multiple").asBool(),
                    category.get("tags").asArray().toList().map { tag -> SystemTag(tag.getString("id"), tag.getString("label")) })
            }
            require(categories.sumOf { it.tags.size } <= 1000)
            TagCatalog(kind, categories)
        })

    fun detail(token: String?, id: String, done: (ApiResponse<LibraryItem>) -> Unit): RequestHandle =
        request("GET", "/items/${safeId(id)}", token, null, done, ::parseItem)

    fun source(token: String?, item: LibraryItem, done: (ApiResponse<ByteArray>) -> Unit): RequestHandle =
        requestBytes("GET", "/items/${safeId(item.id)}/source", token, null, done, MAX_SOURCE_BYTES)

    fun image(token: String?, item: LibraryItem, done: (ApiResponse<ByteArray>) -> Unit): RequestHandle =
        requestBytes("GET", "/items/${safeId(item.id)}/image", token, null, done, MAX_IMAGE_BYTES)

    fun upload(token: String, kind: ContentKind, bytes: ByteArray, done: (ApiResponse<UploadReceipt>) -> Unit): RequestHandle {
        require(bytes.size in 1..Bounds.MAX_SOURCE_BYTES)
        return request("POST", "/uploads?kind=${kind.name}", token, bytes, done, ::parseUpload,
            mapOf("Content-Type" to "application/octet-stream", "Idempotency-Key" to "up_" + UUID.randomUUID()))
    }

    fun create(token: String, assetId: String, previewRequestId: String, name: String,
               description: String, tags: List<String>, done: (ApiResponse<LibraryItem>) -> Unit): RequestHandle {
        Bounds.requireMetadata(name, description, tags)
        val body = Jval.newObject().put("assetId", assetId).put("previewRequestId", previewRequestId)
            .put("name", name).put("description", description)
            .put("tags", Jval.newArray().also { tags.forEach(it::add) }).toString().toByteArray()
        return request("POST", "/items", token, body, done, ::parseItem, mapOf("Idempotency-Key" to "create_${UUID.randomUUID()}"))
    }

    fun edit(token: String, item: LibraryItem, name: String, description: String, tags: List<String>,
             done: (ApiResponse<LibraryItem>) -> Unit): RequestHandle {
        Bounds.requireMetadata(name, description, tags)
        val body = Jval.newObject().put("revision", item.revision).put("name", name)
            .put("description", description).put("tags", Jval.newArray().also { tags.forEach(it::add) }).toString().toByteArray()
        return request("POST", "/items/${safeId(item.id)}/edit", token, body, done, ::parseItem, mapOf("Idempotency-Key" to "edit_${UUID.randomUUID()}"))
    }

    fun submit(token: String, item: LibraryItem, done: (ApiResponse<LibraryItem>) -> Unit): RequestHandle =
        mutation(token, "/items/${safeId(item.id)}/submit", item.revision, done)

    fun moderate(token: String, item: LibraryItem, decision: String, reason: String,
                 done: (ApiResponse<LibraryItem>) -> Unit): RequestHandle {
        require(decision == "HIDE" || decision == "RESTORE")
        require(reason.isNotBlank() && reason.length <= 2000)
        val body = Jval.newObject().put("revision", item.revision).put("decision", decision).put("reason", reason).toString().toByteArray()
        return request("POST", "/items/${safeId(item.id)}/moderate", token, body, done, ::parseItem, mapOf("Idempotency-Key" to "moderate_${UUID.randomUUID()}"))
    }

    fun rate(token: String, item: LibraryItem, score: Int, done: (ApiResponse<LibraryItem>) -> Unit): RequestHandle {
        require(score in 1..5)
        val body = Jval.newObject().put("score", score).toString().toByteArray()
        return request("POST", "/items/${safeId(item.id)}/rating", token, body, done, ::parseItem, mapOf("Idempotency-Key" to "rate_${UUID.randomUUID()}"))
    }

    private fun mutation(token: String, path: String, revision: Long, done: (ApiResponse<LibraryItem>) -> Unit) =
        request("POST", path, token, Jval.newObject().put("revision", revision).toString().toByteArray(), done, ::parseItem, mapOf("Idempotency-Key" to "submit_${UUID.randomUUID()}"))

    private fun <T> request(method: String, path: String, token: String?, body: ByteArray?, done: (ApiResponse<T>) -> Unit,
                             parser: (String) -> T, headers: Map<String, String> = emptyMap()): RequestHandle {
        val connection = AtomicReference<HttpURLConnection?>()
        val future = executor.submit {
            val result = try {
                val response = execute(method, path, token, body, headers, connection, MAX_JSON_BYTES)
                if (response.status in 200..299) try { ApiResponse(parser(response.text), response.status) }
                catch (_: Exception) { ApiResponse(status = response.status, error = "MALFORMED_RESPONSE") }
                else ApiResponse(status = response.status, error = response.text.take(256))
            } catch (_: Exception) { ApiResponse(status = 0, error = "SERVICE_UNAVAILABLE") }
            if (!Thread.currentThread().isInterrupted) done(result)
        }
        return RequestHandle(future, connection)
    }

    private fun requestBytes(method: String, path: String, token: String?, body: ByteArray?, done: (ApiResponse<ByteArray>) -> Unit, maxBytes: Int): RequestHandle {
        val connection = AtomicReference<HttpURLConnection?>()
        val future = executor.submit {
            val result = try { val response = execute(method, path, token, body, emptyMap(), connection, maxBytes); if (response.status in 200..299) ApiResponse(response.bytes, response.status) else ApiResponse(status = response.status, error = response.text.take(256)) } catch (_: Exception) { ApiResponse(status = 0, error = "SERVICE_UNAVAILABLE") }
            if (!Thread.currentThread().isInterrupted) done(result)
        }
        return RequestHandle(future, connection)
    }

    private data class Raw(val status: Int, val text: String, val bytes: ByteArray)

    private fun execute(method: String, path: String, token: String?, body: ByteArray?, headers: Map<String, String>, owner: AtomicReference<HttpURLConnection?>, maxBytes: Int): Raw {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            owner.set(this)
            instanceFollowRedirects = false
            requestMethod = method; connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Hubdustry-Protocol", "1")
            setRequestProperty("X-Hubdustry-Mod-Version", "0.2.0")
            setRequestProperty("X-Hubdustry-Game-Version", "160.2")
            setRequestProperty("X-Hubdustry-Platform", if (System.getProperty("java.runtime.name", "").contains("Android", true)) "android" else "desktop")
            setRequestProperty("X-Correlation-Id", "corr_${UUID.randomUUID()}")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) { doOutput = true; if (!headers.containsKey("Content-Type")) setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(body) } }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input -> ByteArrayOutputStream().also { out -> val buffer = ByteArray(8192); var total = 0; while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= maxBytes) { "response too large" }; out.write(buffer, 0, count) } }.toByteArray() } ?: ByteArray(0)
            Raw(status, String(bytes, StandardCharsets.UTF_8), bytes)
        } finally { connection.disconnect(); owner.compareAndSet(connection, null) }
    }

    private fun parsePage(raw: String): LibraryPage {
        val root = Jval.read(raw); require(root.isObject())
        val items = ArrayList<LibraryItem>()
        for (item in root.get("items").asArray()) items += parseItem(item)
        return LibraryPage(items, root.getInt("total", 0), root.getInt("offset", 0), root.getInt("limit", 24))
    }

    private fun parseCapabilities(raw: String): LibraryCapabilities {
        val x = Jval.read(raw); val names = LinkedHashSet<String>()
        if (x.has("capabilities") && !x.get("capabilities").isNull) for (n in x.get("capabilities").asArray()) names += if (n.isObject) n.getString("name") else n.asString()
        return LibraryCapabilities(nullableString(x, "actorId"), names)
    }

    private fun parseUpload(raw: String): UploadReceipt { val x = Jval.read(raw); return UploadReceipt(x.getString("assetId"), x.getString("previewRequestId"), ContentKind.valueOf(x.getString("kind")), x.getString("sha256"), x.getLong("sizeBytes", 0)) }
    private fun parseItem(raw: String): LibraryItem = parseItem(Jval.read(raw))
    private fun parseItem(x: Jval): LibraryItem {
        val tags = ArrayList<String>()
        if (x.has("tags")) for (tag in x.get("tags").asArray()) tags += tag.asString()
        val attribution = if (x.has("attribution") && !x.get("attribution").isNull && x.get("attribution").isObject) {
            val a = x.get("attribution")
            Attribution(nullableString(a, "creditName"), nullableString(a, "authorId"),
                a.has("identityVerified") && a.get("identityVerified").asBool(),
                nullableString(a, "sourceUrl"),
                if (a.has("createdAt") && !a.get("createdAt").isNull) a.getLong("createdAt", 0) else null,
                a.has("verified") && a.get("verified").asBool(),
                nullableString(a, "licenseNotice"))
        } else null
        val permissions = if (x.has("capabilities")) {
            val names = LinkedHashSet<String>(); for (n in x.get("capabilities").asArray()) names += if (n.isObject) n.getString("name") else n.asString(); LibraryCapabilities(if (x.has("actorId")) x.getString("actorId") else null, names)
        } else LibraryCapabilities()
        val rank = if (x.has("rank") && !x.get("rank").isNull && x.get("rank").isObject) {
            val r = x.get("rank")
            RatingSummary(if (r.has("score")) r.get("score").asDouble() else 0.0,
                if (r.has("tier")) RankTier.valueOf(r.getString("tier")) else RankTier.NEW,
                r.getLong("ratingCount", 0), r.getLong("expertCount", 0), r.getString("version", "library-rank-v1"))
        } else null
        return LibraryItem(x.getString("id"), ContentKind.valueOf(x.getString("kind")), x.getString("name"), x.getString("description", ""), tags,
            if (x.has("uploaderId") && !x.get("uploaderId").isNull) x.getString("uploaderId") else null,
            if (x.has("ownerId") && !x.get("ownerId").isNull) x.getString("ownerId") else null, attribution,
            x.getLong("revision", 0), LibraryState.valueOf(x.getString("state")), x.getString("sha256"), x.getLong("sizeBytes", 0), x.getString("assetId"), x.getString("previewRequestId"), if (x.has("artifactId") && !x.get("artifactId").isNull) x.getString("artifactId") else null, if (x.has("width") && !x.get("width").isNull) x.getInt("width", 0) else null, if (x.has("height") && !x.get("height").isNull) x.getInt("height", 0) else null, if (x.has("createdAt") && !x.get("createdAt").isNull) x.getLong("createdAt", 0) else null, if (x.has("updatedAt") && !x.get("updatedAt").isNull) x.getLong("updatedAt", 0) else null, permissions, rank)
    }
    private fun nullableString(value: Jval, key: String): String? = if (value.has(key) && !value.get(key).isNull) value.getString(key) else null
    private fun safeId(id: String): String { require(id.matches(Regex("li_[a-f0-9]{32}"))); return id }
}

data class UploadReceipt(val assetId: String, val previewRequestId: String, val kind: ContentKind, val sha256: String, val sizeBytes: Long)
