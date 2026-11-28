package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.data.repository.MOVIE_RELEASE_BATCH_LIMIT
import com.nuvio.tv.data.repository.MovieRelease
import com.nuvio.tv.data.repository.MovieReleaseReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Main-thread batcher. A lookup belongs to one set of search results and one profile. */
internal class MovieReleaseLoader(
    private val scope: CoroutineScope,
    private val lookup: suspend (Int, List<String>) -> MovieReleaseReply,
    private val publish: (Int, Map<String, MovieRelease>) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val seen = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val queue = linkedSetOf<String>()
    private val retryAfter = mutableMapOf<String, Long>()
    private var connection = Semaphore(1)
    private var work = SupervisorJob(scope.coroutineContext[Job])
    private var flush: Job? = null

    fun reset() {
        work.cancel()
        work = SupervisorJob(scope.coroutineContext[Job])
        flush = null
        seen.clear()
        pending.clear()
        queue.clear()
        retryAfter.clear()
        connection = Semaphore(1)
    }

    fun retryUnsettled() {
        seen.removeAll(retryAfter.keys)
        retryAfter.clear()
    }

    fun isPending(id: String): Boolean = id in pending

    fun request(profile: Int, ids: Sequence<String>, revisit: Boolean = true) {
        val now = nowMs()
        for (id in ids) {
            val retryAt = retryAfter[id]
            if (seen.add(id) || revisit && retryAt != null && now >= retryAt) {
                retryAfter.remove(id)
                queue.add(id)
                pending.add(id)
            }
        }
        if (queue.isEmpty() || flush?.isActive == true) return
        val generation = work
        val limiter = connection
        flush = scope.launch(generation) {
            // Coalesce rows arriving together and neighboring cards composed in the same frame.
            delay(100)
            val batches = queue.chunked(MOVIE_RELEASE_BATCH_LIMIT)
            queue.clear()
            flush = null
            for (batch in batches) {
                scope.launch(generation) batchLookup@ {
                    val waits = listOf(0L, 2_000L, 4_000L, 8_000L)
                    for ((index, wait) in waits.withIndex()) {
                        delay(wait)
                        val reply = try {
                            limiter.withPermit { lookup(profile, batch) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (!isActive || work !== generation) return@batchLookup
                        val more = reply?.pending == true && index < waits.lastIndex
                        if (!more) {
                            pending.removeAll(batch.toSet())
                            // A cold queue may outlive the automatic polls. Visiting the cards
                            // again can recheck after a cooldown, including after transient errors.
                            val now = nowMs()
                            for (id in batch) {
                                if (reply == null || reply.pending) retryAfter[id] = now + 15_000L
                                else if (reply.releases[id]?.status.let { it == null || it == "unknown" }) {
                                    // Negative provider answers are cached server-side and can change.
                                    retryAfter[id] = now + 300_000L
                                }
                            }
                        }
                        val updates = reply?.releases.orEmpty().filterKeys { it in batch }
                        publish(profile, updates)
                        if (!more) break
                    }
                }
            }
        }
    }
}
