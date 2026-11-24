package com.nuvio.tv.ui.screens.stream

import android.util.Log
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal object HouseholdAutoPlay {
    private const val MAX_ATTEMPTS = 4
    private val signedToken = Regex("[A-Za-z0-9_-]+\\.[0-9a-f]{64}")

    // The app-wide addon client records request URLs and permits redirects.
    // These private capabilities must go directly to the guard, including on debug builds.
    private val http by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    internal fun guardEndpoint(base: HttpUrl, stream: Stream): HttpUrl? {
        if (stream.householdManualSourceId() == null) return null
        val url = stream.url?.toHttpUrlOrNull() ?: return null
        val parts = base.host.split('.')
        if (parts.size != 4) return null
        val address = parts.map { part ->
            // Require canonical decimal octets; other hostnames would trigger a DNS lookup.
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255 || octet.toString() != part) return null
            octet
        }
        val privateLan = address[0] == 10 || (address[0] == 172 && address[1] in 16..31) ||
            (address[0] == 192 && address[1] == 168)
        if (!privateLan) return null
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        val prefix = base.encodedPath.trimEnd('/') + "/play/"
        if (prefix == "/play/" || !url.encodedPath.startsWith(prefix)) return null
        val token = url.encodedPath.removePrefix(prefix)
        return url.takeIf { token.length <= 4096 && signedToken.matches(token) }
    }

    suspend fun verify(
        first: Stream,
        offers: List<Stream>,
        base: HttpUrl,
        settings: PlayerSettings,
        installedAddonNames: Set<String>,
        preferredGroup: String?,
        probe: suspend (HttpUrl) -> Int? = ::head
    ): Stream? {
        val (preferred, rest) = if (preferredGroup != null) {
            offers.partition { it.behaviorHints?.bingeGroup == preferredGroup }
        } else emptyList<Stream>() to offers
        val candidates = sequenceOf(first) + preferred.asSequence() +
            (if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) emptySequence() else rest.asSequence())
        val seenIds = mutableSetOf<String>()
        val seenFiles = mutableSetOf<Pair<String, Long>>()
        var attempts = 0
        for (stream in candidates) {
            val id = stream.householdManualSourceId() ?: continue
            val endpoint = guardEndpoint(base, stream) ?: continue
            if (selectRememberedHouseholdSource(listOf(stream), id, settings, installedAddonNames) == null) continue
            if (!seenIds.add(id)) continue
            val hints = stream.behaviorHints
            val filename = hints?.filename
            val size = hints?.videoSize
            if (!filename.isNullOrBlank() && size != null && size > 0 && !seenFiles.add(filename to size)) continue

            attempts++
            when (probe(endpoint)) {
                302 -> return stream
                409, 410, 503 -> if (attempts >= MAX_ATTEMPTS) break
                else -> break
            }
        }
        return null
    }

    internal suspend fun head(url: HttpUrl): Int? {
        val call = http.newCall(Request.Builder().url(url).head().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.use {
                        if (it.code == 302 && it.header("Location").isNullOrBlank()) null else it.code
                    }
                    Log.i("HouseholdAutoPlay", "HEAD status=${code ?: 0}")
                    if (continuation.isActive) continuation.resume(code)
                }
            })
        }
    }
}
