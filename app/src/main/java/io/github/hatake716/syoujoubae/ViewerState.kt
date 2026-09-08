package io.github.hatake716.syoujoubae

/** Camera state survives pane resizing, orientation changes and tab navigation. */
class CameraState {
    @Volatile var yaw = -6f
    @Volatile var pitch = 85f
    @Volatile var zoom = 1.25f
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    var center = FloatArray(3)
    var fitHalfWidth = 1f
    var fitHalfHeight = 1f
    var poseVersion = -1
    var focusVersion = -1
}

object PaneRatio {
    const val MIN = .20f
    const val MAX = .85f
    fun clamp(value: Float) = if (value.isFinite()) value.coerceIn(MIN, MAX) else .60f
    fun afterDrag(value: Float, delta: Float, available: Float): Float =
        if (available > 0 && delta.isFinite()) clamp(value + delta / available) else clamp(value)
}
