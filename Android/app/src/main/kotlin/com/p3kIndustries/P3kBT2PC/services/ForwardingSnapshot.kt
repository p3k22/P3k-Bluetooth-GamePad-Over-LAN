package com.p3kIndustries.P3kBT2PC.services

import com.p3kIndustries.P3kBT2PC.models.PadState

/** The connection state the screen shows. */
enum class ForwardingStatus { NOT_CONNECTED, SEARCHING, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

/** An immutable copy of everything the screen shows about the forwarder, taken at one moment. */
data class ForwardingSnapshot(
    val status: ForwardingStatus,
    /** The Windows address in use, or the last one used; blank when none was ever entered. */
    val host: String,
    val port: Int,
    /** The latest problem, or null. Cleared when a new attempt starts or a connection succeeds. */
    val error: String?,
    val stats: LinkStats,
    val touchMode: Boolean,
    /** The gamepad's name, or the touchscreen description in touch mode. */
    val deviceName: String,
    /** Percent 0..100, or null when there is no controller or Android does not report a level. */
    val controllerBattery: Int?,
    /** Android's average delay handing controller events to the app, in ms; null in touch mode or before input. */
    val phoneInputMeanMs: Double?,
    val padState: PadState,
    /** True while a connection is being made, is up or is being retried: the Connect button then reads Disconnect. */
    val isBusy: Boolean,
) {
    val isSearching get() = status == ForwardingStatus.SEARCHING
}
