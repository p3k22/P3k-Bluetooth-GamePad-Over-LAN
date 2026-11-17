package com.p3kIndustries.P3kBT2PC

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p3kIndustries.P3kBT2PC.models.PadState
import com.p3kIndustries.P3kBT2PC.models.InputSnapshot
import com.p3kIndustries.P3kBT2PC.services.Server
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real LAN check. These are generated test inputs, NOT physical controller presses. */
@RunWith(AndroidJUnit4::class)
class LanForwardingTest {
    @Test fun orderedInputsAndAcknowledgements() {
        val host = InstrumentationRegistry.getArguments().getString("host")
        org.junit.Assume.assumeTrue("Pass -e host WINDOWS_IP to run the LAN check", !host.isNullOrBlank())
        var status = ""
        val sender = Server { status = it.message }
        fun snapshot(state: PadState, source: String) = InputSnapshot(state, source,
            "Automated LAN test (not physical presses)", SystemClock.elapsedRealtimeNanos())
        fun waitUntil(timeout: Long = 5000, ready: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5)
            assertTrue("Timed out: " + status, ready())
        }
        try {
            sender.connect(host!!, 8080, snapshot(PadState(), "bluetooth"))
            waitUntil { sender.connected }
            waitUntil { sender.stats.acknowledged >= 1 }
            // Let cold virtual-controller creation complete before timing the input sequence.
            Thread.sleep(100)
            for (source in listOf("bluetooth", "touch")) {
                for (i in 0 until 10) {
                    sender.send(snapshot(PadState(mapOf("buttonX" to 1f, "r2" to 1f)), source))
                    Thread.sleep(70)
                    sender.send(snapshot(PadState(), source))
                    Thread.sleep(70)
                }
            }
            waitUntil { sender.stats.acknowledged >= 41 }
            assertTrue("Link unexpectedly disconnected: " + status, sender.connected)
            val stats = sender.stats
            println("GENERATED_LAN_TEST sent=" + stats.sent + " ack=" + stats.acknowledged +
                " meanRttMs=" + stats.meanRttMs + " p95RttMs=" + stats.p95RttMs +
                " applyUs=" + stats.applyUs + " squareDownsGenerated=20")
        } finally { sender.disconnect() }
    }
}