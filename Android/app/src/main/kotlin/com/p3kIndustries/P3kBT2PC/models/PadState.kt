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

data class InputSnapshot(val state: PadState, val source: String, val deviceName: String, val capturedNs: Long)
