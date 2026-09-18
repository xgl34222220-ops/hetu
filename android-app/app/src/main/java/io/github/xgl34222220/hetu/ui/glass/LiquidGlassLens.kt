// SPDX-License-Identifier: Apache-2.0
package io.github.xgl34222220.hetu.ui.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

internal fun BackdropEffectScope.liquidGlassLens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!isRuntimeShaderSupported() || refractionHeight <= 0f || refractionAmount <= 0f) return
    if (padding < refractionAmount) padding = refractionAmount
    val radii = roundedRectCornerRadii() ?: return
    val dispersion = chromaticAberration > 0f
    val scale = downscaleFactor.coerceAtLeast(1).toFloat()
    runtimeShaderEffect(
        key = if (dispersion) "HetuLiquidLensDispersion" else "HetuLiquidLens",
        shaderString = if (dispersion) ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER else ROUNDED_RECT_REFRACTION_SHADER,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", size.width / scale, size.height / scale)
        setFloatUniform("offset", -padding / scale, -padding / scale)
        setFloatUniform("cornerRadii", FloatArray(radii.size) { radii[it] / scale })
        setFloatUniform("refractionHeight", refractionHeight / scale)
        setFloatUniform("refractionAmount", -refractionAmount / scale)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (dispersion) setFloatUniform("chromaticAberration", chromaticAberration)
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val cornerShape = shape as? CornerBasedShape ?: return null
    val maxRadius = size.minDimension / 2f
    val ltr = layoutDirection == LayoutDirection.Ltr
    val tl = if (ltr) cornerShape.topStart.toPx(size, this) else cornerShape.topEnd.toPx(size, this)
    val tr = if (ltr) cornerShape.topEnd.toPx(size, this) else cornerShape.topStart.toPx(size, this)
    val br = if (ltr) cornerShape.bottomEnd.toPx(size, this) else cornerShape.bottomStart.toPx(size, this)
    val bl = if (ltr) cornerShape.bottomStart.toPx(size, this) else cornerShape.bottomEnd.toPx(size, this)
    return floatArrayOf(tl.fastCoerceAtMost(maxRadius), tr.fastCoerceAtMost(maxRadius), br.fastCoerceAtMost(maxRadius), bl.fastCoerceAtMost(maxRadius))
}

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) { if (coord.y <= 0.0) return radii.y; else return radii.z; }
    else { if (coord.y <= 0.0) return radii.x; else return radii.w; }
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) return sign(coord) * normalize(max(cornerCoord, 0.0));
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(coord) * float2(gradX, 1.0 - gradX);
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content; uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect;
$ROUNDED_RECT_SDF
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    return content.eval(coord + d * grad);
}
"""

private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
uniform shader content; uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect; uniform float chromaticAberration;
$ROUNDED_RECT_SDF
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    float dispersion = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 split = d * grad * dispersion; half4 c = half4(0.0);
    half4 a = content.eval(refractedCoord + split); c.r += a.r/3.5; c.a += a.a/7.0;
    half4 b = content.eval(refractedCoord + split*(2.0/3.0)); c.r += b.r/3.5; c.g += b.g/7.0; c.a += b.a/7.0;
    half4 d1 = content.eval(refractedCoord + split*(1.0/3.0)); c.r += d1.r/3.5; c.g += d1.g/3.5; c.a += d1.a/7.0;
    half4 e = content.eval(refractedCoord); c.g += e.g/3.5; c.a += e.a/7.0;
    half4 f = content.eval(refractedCoord - split*(1.0/3.0)); c.g += f.g/3.5; c.b += f.b/3.0; c.a += f.a/7.0;
    half4 g = content.eval(refractedCoord - split*(2.0/3.0)); c.b += g.b/3.0; c.a += g.a/7.0;
    half4 h = content.eval(refractedCoord - split); c.r += h.r/7.0; c.b += h.b/3.0; c.a += h.a/7.0;
    return c;
}
"""
