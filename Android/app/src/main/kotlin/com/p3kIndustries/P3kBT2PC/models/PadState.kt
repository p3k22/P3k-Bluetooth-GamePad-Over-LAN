package com.p3kIndustries.P3kBT2PC.models

/** Fields that hold a stick or d-pad position rather than a button press. */
private val AXIS_FIELDS = setOf("leftStickX", "leftStickY", "rightStickX", "rightStickY", "dpadX", "dpadY")

/** Android axes: X right-positive, Y down-positive. Triggers are 0..1. */
data class PadState(val values: Map<String, Float> = emptyMap()) {
    fun value(key: String) = values[key] ?: 0f

    fun with(key: String, value: Float) = PadState(values + (key to value))

    /** Names of the buttons and triggers currently held past half way. */
    fun pressed() = values.filter { (field, value) -> value > .5f && field !in AXIS_FIELDS }.keys
}

/** Where a [PadState] came from; [protocolName] is the value of the frame's "source" field. */
enum class InputSource(val protocolName: String) {
    BLUETOOTH("bluetooth"),
    TOUCH("touch"),
}

/** One state to send to Windows. [controllerBattery] is a percentage, or null when unknown or not a controller. */
data class InputSnapshot(
    val state: PadState,
    val source: String,
    val deviceName: String,
    val capturedNs: Long,
    val controllerBattery: Int? = null,
)
