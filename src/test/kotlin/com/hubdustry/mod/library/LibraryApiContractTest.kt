package com.hubdustry.mod.library

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class LibraryApiContractTest {
    @Test fun parsesAuthoritativeItemAndSendsProtocolHeaders() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val item = """{"id":"li_12345678901234567890123456789012","kind":"MAP","name":"Map","description":"d","tags":["base"],"ownerId":"owner","uploaderId":"uploader","revision":1,"state":"HIDDEN","sha256":"${"a".repeat(64)}","sizeBytes":3,"assetId":"asset","previewRequestId":"preview","artifactId":null,"width":64,"height":32,"createdAt":1700000000000,"updatedAt":1700000001000,"rank":{"score":4.5,"tier":"QUALITY","ratingCount":3,"expertCount":1,"version":"library-rank-v1"},"attribution":{"creditName":"Author","sourceUrl":null,"createdAt":1700000000000,"verified":false,"authorId":null,"identityVerified":false,"licenseNotice":null},"capabilities":["library.edit.own","library.rate"]}"""
        var protocol = ""; var mod = ""; var game = ""; var platform = ""; var correlation = ""; var query = ""
        server.createContext("/v1/library/items") { exchange ->
            protocol = exchange.requestHeaders.getFirst("X-Hubdustry-Protocol")
            mod = exchange.requestHeaders.getFirst("X-Hubdustry-Mod-Version")
            game = exchange.requestHeaders.getFirst("X-Hubdustry-Game-Version")
            platform = exchange.requestHeaders.getFirst("X-Hubdustry-Platform")
            correlation = exchange.requestHeaders.getFirst("X-Correlation-Id")
            query = exchange.requestURI.query
            val body = """{"items":[$item],"total":1,"offset":0,"limit":24}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val latch = CountDownLatch(1); var page: LibraryPage? = null
            LibraryApi("http://127.0.0.1:${server.address.port}", executor).list(null, LibraryQuery(kind = ContentKind.MAP)) { response -> page = response.value; latch.countDown() }
            latch.await(); val parsed = assertNotNull(page).items.single()
            assertEquals("1", protocol); assertEquals("0.2.0", mod); assertEquals("160.2", game); assertEquals("desktop", platform)
            assertEquals(true, correlation.startsWith("corr_"))
            assertEquals("kind=MAP&sort=recommended&offset=0&limit=24", query)
            assertEquals(LibraryState.HIDDEN, parsed.state); assertEquals("uploader", parsed.uploaderId); assertEquals("owner", parsed.ownerId)
            assertEquals("library-rank-v1", parsed.rank?.version); assertEquals(1700000000000, parsed.createdAt); assertEquals(true, parsed.capabilities.canEdit); assertNull(parsed.attribution?.authorId); assertNull(parsed.attribution?.sourceUrl); assertNull(parsed.artifactId)
        } finally { executor.shutdownNow(); server.stop(0) }
    }

    @Test fun everyMutationCarriesIdempotencyAndCanonicalCorrelation() {
        val server = HttpServer.create(InetSocketAddress(0), 0); val methods = mutableListOf<String>(); val keys = mutableListOf<String?>(); val correlations = mutableListOf<String?>()
        val itemJson = """{"id":"li_12345678901234567890123456789012","kind":"MAP","name":"n","description":"","tags":[],"ownerId":"o","uploaderId":"u","revision":1,"state":"DRAFT","sha256":"${"a".repeat(64)}","sizeBytes":1,"assetId":"as","previewRequestId":"pr","artifactId":null,"width":null,"height":null,"createdAt":1,"updatedAt":1,"rank":{"score":3.0,"tier":"NEW","ratingCount":0,"expertCount":0,"version":"library-rank-v1"},"attribution":null,"capabilities":[]}"""
        server.createContext("/v1/library") { exchange ->
            methods += exchange.requestMethod; keys += exchange.requestHeaders.getFirst("Idempotency-Key"); correlations += exchange.requestHeaders.getFirst("X-Correlation-Id")
            val body = if (exchange.requestURI.path.endsWith("uploads")) "{\"assetId\":\"as\",\"previewRequestId\":\"pr\",\"kind\":\"MAP\",\"sha256\":\"${"a".repeat(64)}\",\"sizeBytes\":1}" else itemJson; val bytes = body.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }; server.start(); val executor = Executors.newFixedThreadPool(2)
        try {
            val api = LibraryApi("http://127.0.0.1:${server.address.port}", executor); val item = LibraryItem("li_12345678901234567890123456789012", ContentKind.MAP, "n", "", emptyList(), "u", "o", null, 1, LibraryState.DRAFT, "${"a".repeat(64)}", 1, "as", "pr", null, null, null, null, null, LibraryCapabilities(), null); val latch = CountDownLatch(5)
            var failures = 0; api.create("t", "as", "pr", "n", "", emptyList()) { if (it.error != null) failures++; latch.countDown() }; api.edit("t", item, "n", "", emptyList()) { if (it.error != null) failures++; latch.countDown() }; api.submit("t", item) { if (it.error != null) failures++; latch.countDown() }; api.moderate("t", item, "HIDE", "reason") { if (it.error != null) failures++; latch.countDown() }; api.rate("t", item, 4) { if (it.error != null) failures++; latch.countDown() }; assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, failures); assertEquals(5, methods.size); assertTrue(keys.all { it?.length in 16..128 }); assertTrue(correlations.all { it?.startsWith("corr_") == true })
        } finally { executor.shutdownNow(); server.stop(0) }
    }
}
