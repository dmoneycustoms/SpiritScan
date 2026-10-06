package com.nscb.spiritscan.ui.decode

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.air.AirFrame
import com.nscb.spiritscan.decode.DecodeFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AIR HUD. Three layers, all computed from the camera alone (see AirEngine):
 *  AMP   = amplified micro-variation: warm = brighter than the band-pass mean, cool = darker (sigma units)
 *  FLOW  = background-oriented-schlieren air motion: teal/white glow + arrows (exaggerated x40)
 *  PULSE = localized periodic pulses with frequency labels
 * Tap the status strip to cycle ALL / AMP / FLOW / PULSE.
 */
private const val AIR_AGSL = """
uniform shader fieldMap;
uniform float2 viewSize;
uniform float2 mapSize;
uniform float time;
uniform float wAmp;
uniform float wFlow;
uniform float wPulse;

half4 tap(float2 p) {
    return fieldMap.eval(clamp(p, float2(0.5, 0.5), mapSize - float2(0.5, 0.5)));
}

half4 samp(float2 p) {
    float2 q = p - float2(0.5, 0.5);
    float2 f = fract(q);
    float2 b = floor(q) + float2(0.5, 0.5);
    half4 a = tap(b);
    half4 c = tap(b + float2(1.0, 0.0));
    half4 d = tap(b + float2(0.0, 1.0));
    half4 e = tap(b + float2(1.0, 1.0));
    return mix(mix(a, c, half(f.x)), mix(d, e, half(f.x)), half(f.y));
}

half4 main(float2 fragCoord) {
    float s = max(viewSize.x / mapSize.x, viewSize.y / mapSize.y);
    float2 off = (viewSize - mapSize * s) * 0.5;
    float2 p = (fragCoord - off) / s;

    half4 m = samp(p);
    float amp = (float(m.r) * 255.0 - 128.0) / 10.0;
    float flow = float(m.g) * 255.0 / 20.0;
    float pul = float(m.b) * 255.0 / 8.0;

    float aAmp = smoothstep(2.0, 6.0, abs(amp)) * wAmp;
    float3 cAmp = float3(0.15, 0.7, 1.0);
    if (amp > 0.0) {
        cAmp = float3(1.0, 0.62, 0.15);
    }
    float aFlow = smoothstep(3.0, 7.0, flow) * wFlow;
    float3 cFlow = mix(float3(0.2, 1.0, 0.8), float3(1.0, 1.0, 1.0), smoothstep(5.0, 10.0, flow));
    float aPul = smoothstep(14.0, 24.0, pul) * wPulse * (0.65 + 0.35 * sin(time * 6.0 + p.y * 0.15));
    float3 cPul = float3(1.0, 0.25, 0.8);

    float a = aAmp * 0.8;
    float3 col = cAmp * a;
    float af = aFlow * 0.75;
    col = col * (1.0 - af) + cFlow * af;
    a = a * (1.0 - af) + af;
    float ap = aPul * 0.8;
    col = col * (1.0 - ap) + cPul * ap;
    a = a * (1.0 - ap) + ap;
    return half4(half3(col), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AirAgsl {
    val shader = RuntimeShader(AIR_AGSL)
    val paint = Paint().apply { shader = this@AirAgsl.shader }
}

private class Flag {
    var bad = false
}

private fun newAirAgsl(): AirAgsl? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return try {
        AirAgsl()
    } catch (_: Throwable) {
        null
    }
}

private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** CPU fallback for devices without AGSL: pick the strongest layer per pixel. */
private fun cpuField(air: AirFrame, layer: Int): IntArray {
    val src = air.pixels
    val out = IntArray(src.size)
    val useAmp = layer == 0 || layer == 1
    val useFlow = layer == 0 || layer == 2
    val usePulse = layer == 0 || layer == 3
    for (i in src.indices) {
        val p = src[i]
        val amp = (((p shr 16) and 0xFF) - 128) / 10f
        val flow = ((p shr 8) and 0xFF) / 20f
        val pul = (p and 0xFF) / 8f
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        if (useAmp) {
            val aa = smooth(2f, 6f, abs(amp)) * 0.8f
            if (aa > a) {
                a = aa
                if (amp > 0f) { r = 1f; g = 0.62f; b = 0.15f } else { r = 0.15f; g = 0.7f; b = 1f }
            }
        }
        if (useFlow) {
            val af = smooth(3f, 7f, flow) * 0.75f
            if (af > a) {
                a = af
                val t = smooth(5f, 10f, flow)
                r = 0.2f + 0.8f * t
                g = 1f
                b = 0.8f + 0.2f * t
            }
        }
        if (usePulse) {
            val ap = smooth(14f, 24f, pul) * 0.8f
            if (ap > a) {
                a = ap
                r = 1f; g = 0.25f; b = 0.8f
            }
        }
        out[i] = ((a * 255f).toInt() shl 24) or ((r * 255f).toInt() shl 16) or ((g * 255f).toInt() shl 8) or (b * 255f).toInt()
    }
    return out
}

private val LayerNames = listOf("ALL", "AMP", "FLOW", "PULSE")
private val AirCyan = Color(0xFF3DE8FF)
private val AirPink = Color(0xFFFF4FD0)
private val AirAmber = Color(0xFFFFB020)

@Composable
fun BoxScope.AirOverlay(air: AirFrame?, frame: DecodeFrame?) {
    var tick by remember { mutableLongStateOf(0L) }
    var layer by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { tick = it }
        }
    }
    val agsl = remember { newAirAgsl() }
    val flag = remember { Flag() }
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            color = android.graphics.Color.WHITE
        }
    }
    val bmp: Bitmap? = remember(air) {
        if (air == null || air.w <= 0 || air.h <= 0 || air.pixels.size < air.w * air.h) null
        else Bitmap.createBitmap(air.pixels, air.w, air.h, Bitmap.Config.ARGB_8888)
    }
    val bmpShader: BitmapShader? = remember(bmp) {
        if (bmp == null) null else BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    val cpuBmp: Bitmap? = remember(air, layer, flag.bad) {
        if (air == null || bmp == null) null
        else if (agsl != null && !flag.bad) null
        else Bitmap.createBitmap(cpuField(air, layer), air.w, air.h, Bitmap.Config.ARGB_8888)
    }

    Canvas(Modifier.fillMaxSize()) {
        val t = tick
        val f = air
        if (f != null && bmp != null && f.warm) {
            val mw = f.w.toFloat()
            val mh = f.h.toFloat()
            val s = max(size.width / mw, size.height / mh)
            val offX = (size.width - mw * s) / 2f
            val offY = (size.height - mh * s) / 2f

            var drawn = false
            if (agsl != null && bmpShader != null && !flag.bad && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    agsl.shader.setInputShader("fieldMap", bmpShader)
                    agsl.shader.setFloatUniform("viewSize", size.width, size.height)
                    agsl.shader.setFloatUniform("mapSize", mw, mh)
                    agsl.shader.setFloatUniform("time", ((t % 1_000_000_000_000L) / 1e9f))
                    agsl.shader.setFloatUniform("wAmp", if (layer == 0 || layer == 1) 1f else 0f)
                    agsl.shader.setFloatUniform("wFlow", if (layer == 0 || layer == 2) 1f else 0f)
                    agsl.shader.setFloatUniform("wPulse", if (layer == 0 || layer == 3) 1f else 0f)
                    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, agsl.paint) }
                    drawn = true
                } catch (_: Throwable) {
                    flag.bad = true
                }
            }
            if (!drawn && cpuBmp != null) {
                drawImage(
                    image = cpuBmp.asImageBitmap(),
                    dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                    dstSize = IntSize((mw * s).roundToInt(), (mh * s).roundToInt()),
                    filterQuality = FilterQuality.Low
                )
            }

            // air-flow arrows (exaggerated x40)
            if (layer == 0 || layer == 2) {
                var count = 0
                for (gy in 0 until f.gh) {
                    for (gx in 0 until f.gw) {
                        if (count >= 150) break
                        val bi = gy * f.gw + gx
                        val dx = f.bosDx[bi]
                        val dy = f.bosDy[bi]
                        val mag = sqrt(dx * dx + dy * dy)
                        if (f.bosSig[bi] < 3.5f || mag < 0.02f) continue
                        val cx = offX + (gx + 0.5f) * f.block * s
                        val cy = offY + (gy + 0.5f) * f.block * s
                        val len = min(mag * s * 40f, 60f)
                        val ux = dx / mag
                        val uy = dy / mag
                        val ex = cx + ux * len
                        val ey = cy + uy * len
                        val c = Color(0xFFB8FFF0).copy(alpha = 0.85f)
                        drawLine(c, Offset(cx, cy), Offset(ex, ey), strokeWidth = 1.6f)
                        val hx = -ux * 6f
                        val hy = -uy * 6f
                        drawLine(c, Offset(ex, ey), Offset(ex + hx - hy * 0.6f, ey + hy + hx * 0.6f), strokeWidth = 1.6f)
                        drawLine(c, Offset(ex, ey), Offset(ex + hx + hy * 0.6f, ey + hy - hx * 0.6f), strokeWidth = 1.6f)
                        count++
                    }
                }
            }

            // pulse sources
            if (layer == 0 || layer == 3) {
                textPaint.textSize = 9.sp.toPx()
                for (p in f.pulses) {
                    val col = if (p.explained) AirAmber else AirPink
                    val cx = offX + p.cx * mw * s
                    val cy = offY + p.cy * mh * s
                    val rw = max(p.halfW * mw * s, 14f)
                    val rh = max(p.halfH * mh * s, 14f)
                    val rad = max(rw, rh)
                    val phase = ((t / 1_000_000L) % 900L) / 900f
                    drawCircle(col.copy(alpha = 0.9f), radius = rad * 0.7f, center = Offset(cx, cy), style = Stroke(2f))
                    drawCircle(col.copy(alpha = 0.55f * (1f - phase)), radius = rad * (0.7f + 0.8f * phase), center = Offset(cx, cy), style = Stroke(1.5f))
                    val label = if (p.explained) "${p.cause} ${"%.1f".format(p.freqHz)}Hz"
                    else "#${p.id} ${"%.1f".format(p.freqHz)}Hz ${"%.0f".format(p.pmrDb)}dB"
                    textPaint.color = android.graphics.Color.argb(
                        255, (col.red * 255).toInt(), (col.green * 255).toInt(), (col.blue * 255).toInt()
                    )
                    val ty = if (cy - rad - 6f > 14f) cy - rad - 6f else cy + rad + 14f
                    drawIntoCanvas { it.nativeCanvas.drawText(label, max(2f, cx - rad), ty, textPaint) }
                }
            }
        }
    }

    // status strip (tap to cycle layers)
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .clickable { layer = (layer + 1) % LayerNames.size }
            .padding(4.dp)
            .background(Color.Black.copy(0.62f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        val a = air
        if (a == null || !a.warm) {
            Text(
                "AIR · waiting for a steady view",
                color = AirCyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                if (frame == null) "camera starting…" else "DECODE ${frame.status.name}: ${frame.note}",
                color = Color(0xFFD7DEE8), fontSize = 8.sp, fontFamily = FontFamily.Monospace
            )
        } else {
            val hot = a.airIndex > 0.3f
            Text(
                "AIR · ${LayerNames[layer]} · flow ${(a.airIndex * 100).toInt()}% · ${"%.0f".format(a.fps)} fps",
                color = if (hot) AirAmber else AirCyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                a.note,
                color = Color(0xFFD7DEE8), fontSize = 8.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                "blocks ${a.validBlocks} usable / ${a.sigBlocks} sig · pulses ${a.pulses.size} · tap = layer",
                color = Color(0xFF8AA0B8), fontSize = 8.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}
