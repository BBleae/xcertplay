package com.shilapi.xcertplay.ultra.egl

/** CPU reference for the fragment shader's premultiplied-alpha opacity contract. */
internal fun scalePremultipliedRgba(
    red: Float,
    green: Float,
    blue: Float,
    alpha: Float,
    opacity: Float,
): FloatArray {
    require(opacity.isFinite() && opacity in 0f..1f)
    return floatArrayOf(
        red * opacity,
        green * opacity,
        blue * opacity,
        alpha * opacity,
    )
}
