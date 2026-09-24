package com.hubdustry.mod.auth

import arc.Core
import arc.util.serialization.Jval
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AccountSession(val accessToken: String, val refreshToken: String, val sessionId: String, val displayName: String?, val avatarRef: String? = null)
data class PairingCreated(val pairingId: String, val browserUrl: String, val collector: String, val pollAfterSeconds: Int)
class AccountRequestHandle internal constructor(private val cancelled: AtomicBoolean) {
    @Volatile private var future: Future<*>? = null
    @Volatile private var connection: HttpURLConnection? = null
    internal fun attach(value: Future<*>) { future = value; if (cancelled.get()) value.cancel(true) }
    internal fun attachConnection(value: HttpURLConnection) { connection = value; if (cancelled.get()) value.disconnect() }
    internal fun clearConnection(value: HttpURLConnection) { if (connection === value) connection = null }
    fun cancel() { cancelled.set(true); future?.cancel(true); connection?.disconnect() }
    internal fun isCancelled() = cancelled.get()
}

/** Canonical browser pairing and rotating sessions. Secrets remain in memory and are never logged. */
class AccountClient(private val origin: String = "https://api.hubdustry.com", private val executor: ExecutorService) {
    @Volatile var session: AccountSession? = null
        private set
    private val epoch = AtomicLong()
    private val active = ConcurrentHashMap.newKeySet<AccountRequestHandle>()
    @Volatile private var pendingRefresh: Triple<String, String, String>? = null
    private val stateLock = Any()
    private val refreshBusy = AtomicBoolean(false)
    private val platform get() = if (System.getProperty("java.runtime.name", "").contains("Android", true)) "android" else "desktop"

    fun pair(done: (Result<PairingCreated>) -> Unit): AccountRequestHandle {
        val cancelled = AtomicBoolean(false); val token = epoch.get()
        val handle = AccountRequestHandle(cancelled); active += handle
        handle.attach(executor.submit {
            runCatching {
                val j = Jval.read(request("POST", "/v1/auth/pairings", null, null, mapOf("Idempotency-Key" to "pair_${UUID.randomUUID()}"), handle))
                val pairingId = j.getString("pairingId"); require(pairingId.matches(Regex("hp_[A-Za-z0-9_-]{1,128}")))
                val browser = URL(j.getString("browserUrl")); val expected = URL(origin.trimEnd('/') + "/auth/pair/")
                val browserPort = if (browser.port == -1) browser.defaultPort else browser.port
                val expectedPort = if (expected.port == -1) expected.defaultPort else expected.port
                require(browser.userInfo == null && browser.query == null && browser.ref == null && browser.protocol == expected.protocol && browser.host == expected.host && browserPort == expectedPort && browser.path in setOf("/auth/pair/$pairingId", "/auth/pair/$pairingId/discord")) { "invalid_pairing_url" }
                PairingCreated(pairingId, browser.toString(), j.getString("collector"), j.getInt("pollAfterSeconds", 2))
            }.onSuccess { created -> if (!cancelled.get() && token == epoch.get()) dispatchIf(handle, token) { Core.app?.openURI(created.browserUrl); done(Result.success(created)) } }
                .onFailure { error -> if (!cancelled.get() && token == epoch.get()) dispatchIf(handle, token) { done(Result.failure(error)) } }
            active.remove(handle)
        })
        return handle
    }

    fun collect(pairing: PairingCreated, done: (Result<AccountSession>) -> Unit): AccountRequestHandle {
        val cancelled = AtomicBoolean(false); val token = epoch.get()
        val handle = AccountRequestHandle(cancelled); active += handle
        handle.attach(executor.submit {
            runCatching {
                var attempts = 0; var status: String
                do {
                    if (cancelled.get() || token != epoch.get()) throw InterruptedException("cancelled")
                    if (++attempts > 120) error("pairing_timeout")
                    Thread.sleep(pairing.pollAfterSeconds.coerceIn(1, 30) * 1000L)
                    val j = Jval.read(request("GET", "/v1/auth/pairings/${pairing.pairingId}", null, null, mapOf("X-Hubdustry-Collector" to pairing.collector), handle))
                    status = j.getString("status")
                } while (status == "pending")
                require(status == "authorized") { "pairing_$status" }
                val j = Jval.read(request("POST", "/v1/auth/pairings/${pairing.pairingId}/collect", null, null, mapOf("X-Hubdustry-Collector" to pairing.collector), handle))
                val identity = if (j.has("account")) j.get("account") else null
                val avatar = identity?.get("avatarRef")?.takeUnless { it.isNull }?.asString()
                AccountSession(j.getString("accessToken"), j.getString("refreshToken"), j.getString("sessionId"), identity?.getString("displayName"), avatar).also { commit(it, token, handle) }
            }.onSuccess { value -> if (!cancelled.get() && token == epoch.get()) dispatchIf(handle, token) { done(Result.success(value)) } }
                .onFailure { error -> if (!cancelled.get() && token == epoch.get()) dispatchIf(handle, token) { done(Result.failure(error)) } }
            active.remove(handle)
        })
        return handle
    }

    fun refresh(done: (Result<AccountSession>) -> Unit): AccountRequestHandle {
        val current = session ?: return AccountRequestHandle(AtomicBoolean(true)).also { done(Result.failure(IllegalStateException("signed_out"))) }
        if (!refreshBusy.compareAndSet(false, true)) return AccountRequestHandle(AtomicBoolean(true)).also { done(Result.failure(IllegalStateException("refresh_in_progress"))) }
        val cancelled = AtomicBoolean(false); val token = epoch.get()
        val retry = pendingRefresh?.takeIf { it.first == current.sessionId }
        val rotationId = retry?.second ?: UUID.randomUUID().toString()
        if (retry == null) pendingRefresh = Triple(current.sessionId, rotationId, current.refreshToken)
        val handle = AccountRequestHandle(cancelled); active += handle
        handle.attach(executor.submit {
            runCatching {
                val body = Jval.newObject().put("refreshToken", retry?.third ?: current.refreshToken).put("rotationId", rotationId).toString().toByteArray()
                val j = Jval.read(request("POST", "/v1/auth/sessions/refresh", null, body, emptyMap(), handle))
                AccountSession(j.getString("accessToken"), j.getString("refreshToken"), j.getString("sessionId"), current.displayName, current.avatarRef).also { commit(it, token, handle) }
            }.onSuccess { value -> pendingRefresh = null; if (!cancelled.get() && token == epoch.get()) dispatchIf(handle, token) { done(Result.success(value)) } }
                .onFailure { error -> if (!cancelled.get() && token == epoch.get()) dispatchIf(handle, token) { done(Result.failure(error)) } }
            refreshBusy.set(false); active.remove(handle)
        })
        return handle
    }

    fun logout() {
        val current = session
        synchronized(stateLock) { epoch.incrementAndGet(); session = null; pendingRefresh = null }
        active.toList().forEach { it.cancel() }; active.clear()
        // The access token is intentionally captured before clearing only when a live session exists.
        // Pending pairing/refresh work is still cancelled and fenced even when no token exists.
        if (current != null) executor.submit { runCatching { request("DELETE", "/v1/auth/sessions/current", current.accessToken, null, emptyMap(), null) } }
    }

    private fun commit(value: AccountSession, token: Long, handle: AccountRequestHandle) {
        synchronized(stateLock) { if (token != epoch.get() || handle.isCancelled()) error("signed_out"); session = value }
    }

    private fun dispatch(action: () -> Unit) { if (Core.app == null) action() else Core.app.post(action) }
    private fun dispatchIf(handle: AccountRequestHandle, token: Long, action: () -> Unit) {
        dispatch { if (!handle.isCancelled() && token == epoch.get()) action() }
    }

    private fun request(method: String, path: String, token: String?, body: ByteArray?, headers: Map<String, String>, handle: AccountRequestHandle?): String {
        val c = (URL(origin.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10_000; readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Hubdustry-Protocol", "1"); setRequestProperty("X-Hubdustry-Mod-Version", "0.2.1"); setRequestProperty("X-Hubdustry-Game-Version", "160.2"); setRequestProperty("X-Hubdustry-Platform", platform); setRequestProperty("X-Correlation-Id", "corr_${UUID.randomUUID().toString().replace("-", "")}")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(body) } }
        }
        handle?.attachConnection(c)
        return try {
            val code = c.responseCode; require(code in 200..299) { "http_$code" }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                while (true) { val read = input.read(buffer); if (read < 0) break; total += read; require(total <= 1_048_576) { "response_too_large" }; out.write(buffer, 0, read) }
                out.toByteArray()
            } ?: ByteArray(0)
            String(bytes, StandardCharsets.UTF_8)
        } finally { c.disconnect(); handle?.clearConnection(c) }
    }
}
