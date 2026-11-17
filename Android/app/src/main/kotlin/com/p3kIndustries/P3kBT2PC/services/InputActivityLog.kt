package com.p3kIndustries.P3kBT2PC.services

import android.os.SystemClock
import com.p3kIndustries.P3kBT2PC.models.PadState
import java.util.ArrayDeque

private const val MAX_ENTRIES = 2000

/** Counts button presses and keeps the most recent input events as text the owner can share. */
class InputActivityLog {
    var controlDowns = 0L
        private set
    var squareDowns = 0L
        private set
    var rawSquareDowns = 0L
        private set
    private val entries = ArrayDeque<String>()

    fun addEntry(message: String) {
        entries.addLast(SystemClock.elapsedRealtime().toString() + " ms  " + message)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /** Counts and logs the buttons that went down or up between two states. */
    fun recordChange(previous: PadState, next: PadState) {
        val newlyPressed = next.pressed() - previous.pressed()
        controlDowns += newlyPressed.size
        if ("buttonX" in newlyPressed) squareDowns++
        newlyPressed.forEach { addEntry("DOWN $it") }
        val newlyReleased = previous.pressed() - next.pressed()
        newlyReleased.forEach { addEntry("UP $it") }
    }

    /** A Square/X press seen on the raw key event, before any mapping. */
    fun recordRawSquareDown() {
        rawSquareDowns++
    }

    fun reset() {
        controlDowns = 0
        squareDowns = 0
        rawSquareDowns = 0
        entries.clear()
    }

    fun shareText(modeName: String, stats: LinkStats): String {
        val header = "P3k Gamepad over LAN\nMode: $modeName"
        val counts = "Sent ${stats.sent}, ack ${stats.acknowledged}, downs $controlDowns, " +
            "Square/X $squareDowns, raw Square $rawSquareDowns"
        val roundTrip = "RTT mean ${stats.meanRttMs} ms, p95 ${stats.p95RttMs} ms"
        return listOf(header, counts, roundTrip, entries.joinToString("\n")).joinToString("\n")
    }
}
