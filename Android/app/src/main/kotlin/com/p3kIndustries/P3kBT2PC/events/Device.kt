package com.p3kIndustries.P3kBT2PC.events

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.p3kIndustries.P3kBT2PC.models.PadState
import kotlin.math.abs
import kotlin.math.max

private const val NO_DEVICE_NAME = "No Bluetooth/USB gamepad connected"
private const val NO_DEVICE_ID = -1

/** Android key code to the protocol field it sets (1 when held, 0 otherwise). */
private val buttonFields = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to "buttonA",
    KeyEvent.KEYCODE_BUTTON_B to "buttonB",
    KeyEvent.KEYCODE_BUTTON_X to "buttonX",
    KeyEvent.KEYCODE_BUTTON_Y to "buttonY",
    KeyEvent.KEYCODE_BUTTON_L1 to "l1",
    KeyEvent.KEYCODE_BUTTON_R1 to "r1",
    KeyEvent.KEYCODE_BUTTON_SELECT to "select",
    KeyEvent.KEYCODE_BUTTON_START to "start",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "leftStickButton",
    KeyEvent.KEYCODE_BUTTON_THUMBR to "rightStickButton",
    KeyEvent.KEYCODE_BUTTON_MODE to "psButton",
    KeyEvent.KEYCODE_BUTTON_L2 to "l2",
    KeyEvent.KEYCODE_BUTTON_R2 to "r2",
)

private val dpadKeys = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

private val triggerFields = setOf("l2", "r2")

/** Turns the key and motion events of the selected physical gamepad into a [PadState]. */
class Device(private val changed: (PadState) -> Unit) {
    var selectedId = NO_DEVICE_ID
        private set
    var name = NO_DEVICE_NAME
        private set
    var state = PadState()
        private set
    private val heldKeys = mutableMapOf<Int, Boolean>()
    private val axisValues = mutableMapOf<String, Float>()

    companion object {
        fun isController(device: InputDevice?) = device != null && !device.isVirtual &&
            (device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK))
    }

    /** Keeps the current gamepad if it is still connected, otherwise picks the first one found. */
    fun refresh() {
        val controllers = InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { isController(it) }
        val selected = controllers.firstOrNull { it.id == selectedId } ?: controllers.firstOrNull()
        if (selected?.id != selectedId) {
            heldKeys.clear()
            axisValues.clear()
            publish()
        }
        selectedId = selected?.id ?: NO_DEVICE_ID
        name = selected?.name ?: NO_DEVICE_NAME
    }

    fun key(event: KeyEvent): Boolean {
        if (event.deviceId != selectedId || !isController(event.device)) return false
        if (!buttonFields.containsKey(event.keyCode) && event.keyCode !in dpadKeys) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        // Repeated down events do not change state, but can restore a held key
        // after foregrounding. Never discard a release because of its repeat field.
        heldKeys[event.keyCode] = event.action == KeyEvent.ACTION_DOWN
        publish()
        return true
    }

    fun motion(event: MotionEvent): Boolean {
        if (event.deviceId != selectedId || !isController(event.device)) return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        val inputDevice = event.device ?: return false
        axisValues["leftStickX"] = readAxis(event, inputDevice, MotionEvent.AXIS_X)
        axisValues["leftStickY"] = readAxis(event, inputDevice, MotionEvent.AXIS_Y)
        axisValues["rightStickX"] = readAxis(event, inputDevice, MotionEvent.AXIS_Z)
        axisValues["rightStickY"] = readAxis(event, inputDevice, MotionEvent.AXIS_RZ)
        axisValues["l2"] = readTrigger(event, inputDevice, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        axisValues["r2"] = readTrigger(event, inputDevice, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        axisValues["dpadX"] = readAxis(event, inputDevice, MotionEvent.AXIS_HAT_X)
        axisValues["dpadY"] = readAxis(event, inputDevice, MotionEvent.AXIS_HAT_Y)
        publish()
        return true
    }

    fun neutral() {
        heldKeys.clear()
        axisValues.clear()
        publish()
    }

    /** The axis value in -1..1, or 0 while inside the device's dead zone. */
    private fun readAxis(event: MotionEvent, inputDevice: InputDevice, axis: Int): Float {
        val value = event.getAxisValue(axis)
        val deadZone = inputDevice.getMotionRange(axis, event.source)?.flat ?: 0f
        return if (abs(value) <= deadZone) 0f else value.coerceIn(-1f, 1f)
    }

    /** Analog trigger value in 0..1; some pads report it on the brake/gas axes instead. */
    private fun readTrigger(event: MotionEvent, inputDevice: InputDevice, primaryAxis: Int, fallbackAxis: Int): Float {
        val axis = if (inputDevice.getMotionRange(primaryAxis) != null) primaryAxis else fallbackAxis
        return readAxis(event, inputDevice, axis).coerceIn(0f, 1f)
    }

    private fun isHeld(keyCode: Int) = if (heldKeys[keyCode] == true) 1f else 0f

    private fun publish() {
        val values = axisValues.toMutableMap()
        buttonFields.forEach { (keyCode, field) ->
            val down = isHeld(keyCode)
            values[field] = if (field in triggerFields) max(values[field] ?: 0f, down) else down
        }
        val dpadX = isHeld(KeyEvent.KEYCODE_DPAD_RIGHT) - isHeld(KeyEvent.KEYCODE_DPAD_LEFT)
        val dpadY = isHeld(KeyEvent.KEYCODE_DPAD_DOWN) - isHeld(KeyEvent.KEYCODE_DPAD_UP)
        if (dpadX != 0f) values["dpadX"] = dpadX
        if (dpadY != 0f) values["dpadY"] = dpadY
        val next = PadState(values.filterValues { it != 0f })
        if (next != state) {
            state = next
            changed(next)
        }
    }
}
