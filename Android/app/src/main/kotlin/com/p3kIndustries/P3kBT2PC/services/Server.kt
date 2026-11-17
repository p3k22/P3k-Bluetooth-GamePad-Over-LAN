package com.p3kIndustries.P3kBT2PC.services

import android.os.SystemClock
import com.p3kIndustries.P3kBT2PC.models.InputSnapshot
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.ceil

private const val PROTOCOL_VERSION = 2
private const val QUEUE_CAPACITY = 512
private const val HEARTBEAT_INTERVAL_MS = 50L
private const val STALE_QUEUE_LIMIT_MS = 250
private const val CONNECT_TIMEOUT_MS = 3000
private const val READ_TIMEOUT_MS = 2000
private const val ACK_READER_JOIN_TIMEOUT_MS = 500L
private const val MAX_ACK_LINE_LENGTH = 4096
private const val RTT_WINDOW_SIZE = 200
private const val NANOS_PER_MILLI = 1e6

data class LinkStats(
    val sent: Long = 0,
    val acknowledged: Long = 0,
    val meanRttMs: Double = 0.0,
    val p95RttMs: Double = 0.0,
    val queueMs: Double = 0.0,
    val applyUs: Double = 0.0,
)

enum class LinkState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

/** A change of the link; [message] describes it and, for [LinkState.FAILED], is the error. */
data class ServerEvent(val state: LinkState, val message: String)

/**
 * One TCP link to the Windows app: queues input snapshots, writes them as JSON lines, reads the acknowledgements.
 * [onEvent] is called on the writer and reader threads, so a caller that needs the main thread must post.
 */
class Server(private val onEvent: (ServerEvent) -> Unit) {
    @Volatile private var session: Session? = null
    @Volatile private var lastStats = LinkStats()

    val stats get() = session?.stats ?: lastStats
    val connected get() = session?.connected == true
    val busy get() = session != null

    @Synchronized
    fun connect(host: String, port: Int, initial: InputSnapshot) {
        disconnect()
        val next = Session(host, port, initial)
        lastStats = LinkStats()
        session = next
        thread(name = "P3k-input-writer") { next.run() }
    }

    fun send(snapshot: InputSnapshot) {
        session?.enqueue(snapshot)
    }

    @Synchronized
    fun disconnect() {
        val previous = session
        if (previous != null) lastStats = previous.stats
        session = null
        previous?.close()
        if (previous != null) onEvent(ServerEvent(LinkState.DISCONNECTED, "Disconnected"))
    }

    private inner class Session(private val host: String, private val port: Int, initial: InputSnapshot) {
        private val socket = Socket()
        private val queue = ArrayBlockingQueue<InputSnapshot>(QUEUE_CAPACITY)
        private val recentRtts = ArrayDeque<Double>()
        private var sentCount = 0L

        @Volatile var connected = false
            private set

        @Volatile private var closed = false

        @Volatile var stats = LinkStats()
            private set

        init {
            queue.add(initial)
        }

        fun enqueue(snapshot: InputSnapshot) {
            if (!closed && !queue.offer(snapshot)) {
                fail("Input queue overflow — disconnected to avoid losing button edges")
            }
        }

        fun close() {
            closed = true
            connected = false
            try {
                socket.close()
            } catch (_: Exception) {
                // Closing an already broken socket has nothing left to clean up.
            }
        }

        /** Runs on the writer thread: connect, start the ack reader, then write frames until closed. */
        fun run() {
            try {
                if (session !== this || closed) return
                if (!connectSocket()) return
                val ackReader = startAckReader()
                writeInputFrames()
                socket.close()
                ackReader.join(ACK_READER_JOIN_TIMEOUT_MS)
            } catch (error: Exception) {
                if (!closed) fail("Connection failed: " + error.message)
            } finally {
                close()
            }
        }

        private fun fail(message: String) {
            close()
            synchronized(this@Server) {
                if (session === this) {
                    lastStats = stats
                    session = null
                    onEvent(ServerEvent(LinkState.FAILED, message))
                }
            }
        }

        /** Returns false when the session was closed while connecting. */
        private fun connectSocket(): Boolean {
            onEvent(ServerEvent(LinkState.CONNECTING, "Connecting to $host:$port"))
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            if (closed) return false
            connected = true
            onEvent(ServerEvent(LinkState.CONNECTED, "Connected to $host:$port"))
            return true
        }

        private fun startAckReader() = thread(name = "P3k-ack-reader") {
            try {
                readAcknowledgements()
            } catch (error: Exception) {
                if (!closed) fail("Connection lost: " + error.message)
            }
        }

        private fun readAcknowledgements() {
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            while (!closed) {
                val line = input.readLine() ?: throw IOException("Windows disconnected")
                if (line.length > MAX_ACK_LINE_LENGTH) throw IOException("Invalid acknowledgement")
                val ack = JSONObject(line)
                if (ack.optString("type") != "ack") continue
                val roundTripMs = (SystemClock.elapsedRealtimeNanos() - ack.getLong("sentNs")) / NANOS_PER_MILLI
                val windowsApplyUs = ack.optDouble("serverApplyUs")
                InputTimingLog.acknowledged(roundTripMs, windowsApplyUs)
                recordAcknowledgement(roundTripMs, windowsApplyUs)
            }
        }

        private fun recordAcknowledgement(roundTripMs: Double, windowsApplyUs: Double) {
            synchronized(recentRtts) {
                recentRtts.addLast(roundTripMs)
                while (recentRtts.size > RTT_WINDOW_SIZE) recentRtts.removeFirst()
                val sorted = recentRtts.sorted()
                stats = stats.copy(
                    acknowledged = stats.acknowledged + 1,
                    meanRttMs = sorted.average(),
                    p95RttMs = percentile95(sorted),
                    applyUs = windowsApplyUs,
                )
            }
        }

        private fun percentile95(sorted: List<Double>): Double {
            val index = (ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)
            return sorted[index]
        }

        /**
         * Sends the first snapshot at once, then every newer snapshot as it arrives. When nothing new
         * arrives within the heartbeat interval the last snapshot is sent again.
         */
        private fun writeInputFrames() {
            val output = socket.getOutputStream()
            var current = queue.take()
            var sequence = 0L
            var isFirstFrame = true
            while (!closed) {
                val queued = if (isFirstFrame) current else pollNextSnapshot()
                isFirstFrame = false
                if (queued != null) current = queued
                val sentNs = SystemClock.elapsedRealtimeNanos()
                val hasQueueDelay = queued != null && sequence > 0
                val queueDelayMs = if (hasQueueDelay) (sentNs - current.capturedNs) / NANOS_PER_MILLI else 0.0
                if (queueDelayMs > STALE_QUEUE_LIMIT_MS) throw IOException("Input queue is stale")
                writeFrame(output, buildInputFrame(sequence, sentNs, current))
                sequence++
                sentCount++
                if (queued != null) InputTimingLog.sent(queueDelayMs)
                recordSent(isQueuedSnapshot = queued != null, queueDelayMs = queueDelayMs)
            }
        }

        private fun pollNextSnapshot() = queue.poll(HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)

        private fun buildInputFrame(sequence: Long, sentNs: Long, snapshot: InputSnapshot): JSONObject {
            val frame = JSONObject()
                .put("type", "input")
                .put("protocol", PROTOCOL_VERSION)
                .put("sequence", sequence)
                .put("sentNs", sentNs)
                .put("source", snapshot.source)
                .put("deviceName", snapshot.deviceName)
                .put("input", JSONObject(snapshot.state.values))
            snapshot.controllerBattery?.let { percent -> frame.put("controllerBattery", percent) }
            return frame
        }

        private fun writeFrame(output: OutputStream, frame: JSONObject) {
            output.write((frame.toString() + "\n").toByteArray(Charsets.UTF_8))
        }

        private fun recordSent(isQueuedSnapshot: Boolean, queueDelayMs: Double) {
            synchronized(recentRtts) {
                stats = stats.copy(
                    sent = sentCount,
                    queueMs = if (isQueuedSnapshot) queueDelayMs else stats.queueMs,
                )
            }
        }
    }
}
