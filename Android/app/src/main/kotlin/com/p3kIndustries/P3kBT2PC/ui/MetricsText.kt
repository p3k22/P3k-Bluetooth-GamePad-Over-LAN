package com.p3kIndustries.P3kBT2PC.ui

import com.p3kIndustries.P3kBT2PC.services.ForwardingSnapshot
import com.p3kIndustries.P3kBT2PC.services.ForwardingStatus
import java.util.Locale

/**
 * The latency lines shown under the gamepad. Controller → PC is estimated as Android's input delay plus half the
 * network round trip; the controller's own radio time is not measurable from the phone and is left out.
 */
fun formatLatency(snapshot: ForwardingSnapshot): String {
    val stats = snapshot.stats
    if (snapshot.status != ForwardingStatus.CONNECTED || stats.acknowledged == 0L) {
        return "Latency: waiting for a connection"
    }
    val networkOneWayMs = stats.meanRttMs / 2
    val phoneInputMs = snapshot.phoneInputMeanMs
    val summary = if (phoneInputMs == null) {
        String.format(Locale.US, "Latency phone → PC ≈ %.1f ms", networkOneWayMs)
    } else {
        String.format(
            Locale.US,
            "Latency controller → PC ≈ %.1f ms  (phone input %.1f ms + network %.1f ms)",
            phoneInputMs + networkOneWayMs, phoneInputMs, networkOneWayMs,
        )
    }
    val roundTrip = String.format(
        Locale.US,
        "Network round trip %.1f ms average · %.1f ms p95",
        stats.meanRttMs, stats.p95RttMs,
    )
    return summary + "\n" + roundTrip
}
