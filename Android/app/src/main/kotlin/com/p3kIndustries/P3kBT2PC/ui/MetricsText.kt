package com.p3kIndustries.P3kBT2PC.ui

import com.p3kIndustries.P3kBT2PC.services.InputActivityLog
import com.p3kIndustries.P3kBT2PC.services.LinkStats
import java.util.Locale

/** The two-line status text shown under the gamepad. */
fun formatMetrics(stats: LinkStats, activity: InputActivityLog): String {
    val counts = String.format(
        Locale.US,
        "Sent %,d · acknowledged %,d · control downs %,d · Square/X %,d (raw %,d)",
        stats.sent, stats.acknowledged, activity.controlDowns, activity.squareDowns, activity.rawSquareDowns,
    )
    val timings = String.format(
        Locale.US,
        "LAN round trip: mean %.2f ms · p95 %.2f ms | send queue %.2f ms | Windows apply %.0f µs",
        stats.meanRttMs, stats.p95RttMs, stats.queueMs, stats.applyUs,
    )
    return counts + "\n" + timings
}
