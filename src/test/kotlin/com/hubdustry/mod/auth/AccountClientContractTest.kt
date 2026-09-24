package com.hubdustry.mod.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountClientContractTest {
    @Test fun pairingRejectsForeignOriginsAndUnexpectedRoutes() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        val browserUrl = java.util.concurrent.atomic.AtomicReference<String>()
        server.createContext("/v1/auth/pairings") { exchange ->
            val body = """{"pairingId":"hp_test","browserUrl":"${browserUrl.get()}","collector":"collector-secret","pollAfterSeconds":1}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }; server.start()
        val executor = Executors.newSingleThreadExecutor(); val client = AccountClient(origin, executor)
        try {
            for (url in listOf("https://example.com/auth/pair/hp_test/discord", "$origin/auth/pair/hp_other/discord", "$origin/auth/pair/hp_test/discord/extra", "$origin/auth/pair/hp_test/discord?redirect=elsewhere", "$origin/auth/pair/hp_test/discord#extra", "http://user@127.0.0.1:${server.address.port}/auth/pair/hp_test/discord")) {
                browserUrl.set(url); val done = CountDownLatch(1); var rejected = false
                client.pair { rejected = it.isFailure; done.countDown() }
                assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS)); assertTrue(rejected)
            }
        } finally { executor.shutdownNow(); server.stop(0) }
    }

    @Test fun pairCollectRefreshLogoutUsesCanonicalHeadersAndFencesLogout() {
        val server = HttpServer.create(InetSocketAddress(0), 0); val polls = AtomicInteger(0); val deleted = CountDownLatch(1)
        val methods = mutableListOf<String>(); val correlations = mutableListOf<String>(); val platforms = mutableListOf<String>()
        server.createContext("/") { exchange ->
            methods += exchange.requestMethod; correlations += exchange.requestHeaders.getFirst("X-Correlation-Id"); platforms += exchange.requestHeaders.getFirst("X-Hubdustry-Platform")
            val path = exchange.requestURI.path
            val body = when {
                path == "/v1/auth/pairings" -> """{"pairingId":"hp_test","browserUrl":"http://127.0.0.1:${server.address.port}/auth/pair/hp_test/discord","collector":"collector-secret","verificationCode":"ABC123","expiresAt":"2026-01-01T00:00:00Z","pollAfterSeconds":1}"""
                path.endsWith("/hp_test") -> if (polls.incrementAndGet() == 1) """{"pairingId":"hp_test","status":"pending","expiresAt":"2026-01-01T00:00:00Z","pollAfterSeconds":1}""" else """{"pairingId":"hp_test","status":"authorized","expiresAt":"2026-01-01T00:00:00Z","pollAfterSeconds":1}"""
                path.endsWith("/collect") -> """{"sessionId":"00000000-0000-0000-0000-000000000001","accessToken":"access-token-123456","refreshToken":"refresh-token-123456","accessExpiresAt":"2026-01-01T00:00:00Z","refreshExpiresAt":"2026-02-01T00:00:00Z","account":{"id":"user","displayName":"Test"}}"""
                path.endsWith("/refresh") -> """{"sessionId":"00000000-0000-0000-0000-000000000002","accessToken":"access-token-abcdef","refreshToken":"refresh-token-abcdef","accessExpiresAt":"2026-01-01T00:00:00Z","refreshExpiresAt":"2026-02-01T00:00:00Z","account":{"id":"user","displayName":"Test"}}"""
                else -> ""
            }.toByteArray()
            exchange.sendResponseHeaders(if (exchange.requestMethod == "DELETE") 204 else 200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            if (exchange.requestMethod == "DELETE") deleted.countDown()
        }; server.start()
        val executor = Executors.newFixedThreadPool(2); val client = AccountClient("http://127.0.0.1:${server.address.port}", executor)
        try {
            val pairLatch = CountDownLatch(1); var pairing: PairingCreated? = null
            client.pair { it.onSuccess { pairing = it }; pairLatch.countDown() }; assertTrue(pairLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val collectLatch = CountDownLatch(1); client.collect(assertNotNull(pairing)) { assertTrue(it.isSuccess); collectLatch.countDown() }; assertTrue(collectLatch.await(4, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull(client.session); val refreshLatch = CountDownLatch(1); client.refresh { assertTrue(it.isSuccess); refreshLatch.countDown() }; assertTrue(refreshLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            client.logout(); assertEquals(null, client.session); assertTrue(deleted.await(2, java.util.concurrent.TimeUnit.SECONDS)); assertTrue(correlations.all { it.startsWith("corr_") }); assertTrue(platforms.all { it == "desktop" }); assertTrue(methods.contains("DELETE"))
        } finally { executor.shutdownNow(); server.stop(0) }
    }

    @Test fun logoutCancelsBlockedCollectWithoutLateSession() {
        val server = HttpServer.create(InetSocketAddress(0), 0); val requestSeen = CountDownLatch(1); val release = CountDownLatch(1)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path == "/v1/auth/pairings") {
                val body = """{"pairingId":"hp_test","browserUrl":"http://127.0.0.1:${server.address.port}/auth/pair/hp_test","collector":"collector-secret","verificationCode":"ABC123","expiresAt":"2026-01-01T00:00:00Z","pollAfterSeconds":1}""".toByteArray(); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            } else {
                requestSeen.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS); exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("{}".toByteArray()) }
            }
        }; server.start()
        val executor = Executors.newFixedThreadPool(2); val client = AccountClient("http://127.0.0.1:${server.address.port}", executor)
        try {
            val pairLatch = CountDownLatch(1); var pairing: PairingCreated? = null; client.pair { it.onSuccess { pairing = it }; pairLatch.countDown() }; assertTrue(pairLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            var callback = false; client.collect(assertNotNull(pairing)) { callback = true }; assertTrue(requestSeen.await(3, java.util.concurrent.TimeUnit.SECONDS)); client.logout(); release.countDown(); Thread.sleep(100); assertEquals(false, callback); assertEquals(null, client.session)
        } finally { executor.shutdownNow(); server.stop(0) }
    }
}
