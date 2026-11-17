package com.p3kIndustries.P3kBT2PC.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.p3kIndustries.P3kBT2PC.models.PadState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** The controls are laid out on a fixed 1000 x 350 canvas that is scaled to fit the view. */
private const val CANVAS_WIDTH = 1000f
private const val CANVAS_HEIGHT = 350f

/** A touch this close outside a control's circle still grabs it. */
private const val GRAB_MARGIN = 8f

/** A held button stays pressed while the finger is within this distance beyond the circle. */
private const val BUTTON_HOLD_MARGIN = 18f

/** The d-pad ignores finger offsets smaller than this. */
private const val DPAD_DEAD_ZONE = 17f

private const val STICK_KNOB_TRAVEL = 37f
private const val STICK_KNOB_RADIUS = 21f
private const val DPAD_MARKER_TRAVEL = 25f
private const val DPAD_MARKER_RADIUS = 10f

private val controlFillColor = Color.rgb(43, 52, 68)
private val controlIdleColor = Color.rgb(105, 125, 151)
private val controlPressedColor = Color.rgb(90, 238, 180)

/** Each pointer owns one control; two sticks and several buttons can be held together. */
class GamepadView(context: Context, private val changed: (PadState) -> Unit) : View(context) {
    data class Control(val key: String, val label: String, val x: Float, val y: Float, val radius: Float = 29f)

    private data class Pointer(val control: Control, var x: Float, var y: Float)

    private val controls = listOf(
        Control("l1", "L1", 70f, 44f),
        Control("l2", "L2", 200f, 44f),
        Control("r2", "R2", 800f, 44f),
        Control("r1", "R1", 930f, 44f),
        Control("select", "Share", 420f, 44f, 34f),
        Control("start", "Options", 580f, 44f, 34f),
        Control("psButton", "PS", 500f, 110f, 25f),
        Control("touchpad", "Touch", 500f, 175f, 30f),
        Control("buttonX", "□ / X", 825f, 195f),
        Control("buttonY", "△ / Y", 890f, 130f),
        Control("buttonB", "○ / B", 955f, 195f),
        Control("buttonA", "× / A", 890f, 260f),
        Control("leftStickButton", "L3", 320f, 310f, 24f),
        Control("rightStickButton", "R3", 670f, 310f, 24f),
        Control("leftStick", "Left stick", 320f, 205f, 65f),
        Control("rightStick", "Right stick", 670f, 205f, 65f),
        Control("dpad", "D-pad", 105f, 205f, 68f),
    )
    private val pointers = mutableMapOf<Int, Pointer>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    var interactive = false
        set(value) {
            field = value
            release()
            invalidate()
        }

    var state = PadState()
        set(value) {
            field = value
            invalidate()
        }

    init {
        contentDescription = "Multitouch gamepad: D-pad, two sticks, face buttons, shoulders and triggers"
        isFocusable = true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        scale = min(width / CANVAS_WIDTH, height / CANVAS_HEIGHT).coerceAtLeast(.01f)
        offsetX = (width - CANVAS_WIDTH * scale) / 2
        offsetY = (height - CANVAS_HEIGHT * scale) / 2
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        controls.forEach { control -> drawControl(canvas, control) }
        canvas.restore()
    }

    private fun isControlDown(control: Control) = when (control.key) {
        "dpad" -> state.value("dpadX") != 0f || state.value("dpadY") != 0f
        "leftStick", "rightStick" -> state.value(control.key + "X") != 0f || state.value(control.key + "Y") != 0f
        else -> state.value(control.key) > .5f
    }

    private fun drawControl(canvas: Canvas, control: Control) {
        val down = isControlDown(control)
        paint.color = controlFillColor
        paint.style = Paint.Style.FILL
        canvas.drawCircle(control.x, control.y, control.radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (down) 3f else 1.5f
        paint.color = if (down) controlPressedColor else controlIdleColor
        canvas.drawCircle(control.x, control.y, control.radius, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 15f
        when (control.key) {
            "leftStick", "rightStick" -> drawStick(canvas, control)
            "dpad" -> drawDpad(canvas, control, down)
            else -> canvas.drawText(control.label, control.x, control.y + 5, paint)
        }
    }

    private fun drawStick(canvas: Canvas, control: Control) {
        val knobX = control.x + state.value(control.key + "X") * STICK_KNOB_TRAVEL
        val knobY = control.y + state.value(control.key + "Y") * STICK_KNOB_TRAVEL
        canvas.drawCircle(knobX, knobY, STICK_KNOB_RADIUS, paint)
        paint.color = Color.LTGRAY
        canvas.drawText(control.label, control.x, control.y + 88, paint)
    }

    private fun drawDpad(canvas: Canvas, control: Control, down: Boolean) {
        canvas.drawText("↑", control.x, control.y - 29, paint)
        canvas.drawText("↓", control.x, control.y + 42, paint)
        canvas.drawText("←", control.x - 39, control.y + 5, paint)
        canvas.drawText("→", control.x + 39, control.y + 5, paint)
        if (down) {
            val markerX = control.x + state.value("dpadX") * DPAD_MARKER_TRAVEL
            val markerY = control.y + state.value("dpadY") * DPAD_MARKER_TRAVEL
            canvas.drawCircle(markerX, markerY, DPAD_MARKER_RADIUS, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> grabControlUnderPointer(event)
            MotionEvent.ACTION_MOVE -> moveGrabbedControls(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> releasePointer(event)
            MotionEvent.ACTION_CANCEL -> pointers.clear()
        }
        publish()
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun release() {
        pointers.clear()
        if (interactive) publish()
    }

    /** The pointer's position in the 1000 x 350 canvas the controls are laid out on. */
    private fun canvasPosition(event: MotionEvent, pointerIndex: Int): Pair<Float, Float> {
        val canvasX = (event.getX(pointerIndex) - offsetX) / scale
        val canvasY = (event.getY(pointerIndex) - offsetY) / scale
        return Pair(canvasX, canvasY)
    }

    private fun grabControlUnderPointer(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        val (pointerX, pointerY) = canvasPosition(event, pointerIndex)
        val hit = controls.firstOrNull { hypot(pointerX - it.x, pointerY - it.y) <= it.radius + GRAB_MARGIN }
        if (hit != null && pointers.values.none { it.control == hit }) {
            pointers[event.getPointerId(pointerIndex)] = Pointer(hit, pointerX, pointerY)
        }
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releasePointer(event: MotionEvent) {
        pointers.remove(event.getPointerId(event.actionIndex))
    }

    private fun moveGrabbedControls(event: MotionEvent) {
        for (pointerIndex in 0 until event.pointerCount) {
            val (pointerX, pointerY) = canvasPosition(event, pointerIndex)
            pointers[event.getPointerId(pointerIndex)]?.let {
                it.x = pointerX
                it.y = pointerY
            }
        }
    }

    private fun publish() {
        val values = mutableMapOf<String, Float>()
        pointers.values.forEach { pointer -> writePointerValues(pointer, values) }
        val next = PadState(values.filterValues { it != 0f })
        if (state != next) {
            state = next
            changed(next)
        }
    }

    private fun writePointerValues(pointer: Pointer, values: MutableMap<String, Float>) {
        val control = pointer.control
        val offsetFromCenterX = pointer.x - control.x
        val offsetFromCenterY = pointer.y - control.y
        when (control.key) {
            "leftStick", "rightStick" -> {
                val length = hypot(offsetFromCenterX, offsetFromCenterY).coerceAtLeast(control.radius)
                values[control.key + "X"] = offsetFromCenterX / length
                values[control.key + "Y"] = offsetFromCenterY / length
            }
            "dpad" -> {
                values["dpadX"] = dpadDirection(offsetFromCenterX)
                values["dpadY"] = dpadDirection(offsetFromCenterY)
            }
            else -> if (hypot(offsetFromCenterX, offsetFromCenterY) <= control.radius + BUTTON_HOLD_MARGIN) {
                values[control.key] = 1f
            }
        }
    }

    private fun dpadDirection(offset: Float) = if (abs(offset) < DPAD_DEAD_ZONE) 0f else if (offset > 0) 1f else -1f
}
