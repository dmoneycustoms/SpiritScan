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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.nscb.spiritscan.decode.Cause
import com.nscb.spiritscan.decode.DecodeFrame
import com.nscb.spiritscan.decode.DecodeStatus
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AGSL (Android 13+) runs on the GPU. The CPU decoder hands it a tiny 120x160 "field" bitmap:
 *   R = |matched-filter sigma| / 9      G = independent flow / 2 px      B = persistence / 30 frames
 * The shader does the bilinear upsample, FILL_CENTER mapping, iso-sigma contours, edge glow,
 * flow tint and the false-colour ramp, so the HUD stays smooth at display refresh rate.
 */
private const val AGSL_SRC = """
uniform shader zmap;
uniform float2 viewSize;
uniform float2 mapSize;
uniform float time;

half4 tap(float2 p) {
    return zmap.eval(clamp(p, float2(0.5, 0.5), mapSize - float2(0.5, 0.5)));
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

float3 inferno(float tin) {
    float t = clamp(tin, 0.0, 1.0);
    float3 c0 = float3(0.00021894, 0.00165100, -0.01948090);
    float3 c1 = float3(0.10651341, 0.56395644, 3.93271230);
    float3 c2 = float3(11.602494, -3.9728539, -15.942394);
    float3 c3 = float3(-41.703995, 17.436398, 44.354145);
    float3 c4 = float3(77.162935, -33.402359, -81.80731);
    float3 c5 = float3(-71.319428, 32.626064, 73.20952);
    float3 c6 = float3(25.13113, -12.242669, -23.070327);
    return clamp(c0 + t * (c1 + t * (c2 + t * (c3 + t * (c4 + t * (c5 + t * c6))))), 0.0, 1.0);
}

half4 main(float2 fragCoord) {
    float s = max(viewSize.x / mapSize.x, viewSize.y / mapSize.y);
    float2 off = (viewSize - mapSize * s) * 0.5;
    float2 p = (fragCoord - off) / s;

    half4 m = samp(p);
    float sig = float(m.r) * 9.0;
    float flow = float(m.g) * 2.0;
    float pers = float(m.b);

    float zx = float(samp(p + float2(1.5, 0.0)).r) - float(samp(p - float2(1.5, 0.0)).r);
    float zy = float(samp(p + float2(0.0, 1.5)).r) - float(samp(p - float2(0.0, 1.5)).r);
    float edge = clamp(length(float2(zx, zy)) * 5.0, 0.0, 1.0);

    float a = smoothstep(2.0, 5.5, sig);
    float3 col = inferno(0.18 + sig / 11.0);
    col = mix(col, float3(0.25, 0.95, 1.0), clamp(flow / 1.5, 0.0, 1.0) * 0.55);

    float iso = smoothstep(0.43, 0.5, abs(fract(sig) - 0.5)) * step(2.0, sig);
    col += float3(0.35, 0.35, 0.35) * iso * 0.6;
    col += float3(1.0, 0.9, 0.8) * edge * 0.5;
    col *= 0.85 + 0.4 * pers;
    col = clamp(col, 0.0, 1.0);

    float scan = 0.92 + 0.08 * sin(fragCoord.y * 0.9 + time * 7.0);
    float alpha = clamp(a * 0.88 * scan + edge * a * 0.3, 0.0, 0.95);
    return half4(half3(col * alpha), half(alpha));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslHolder {
    val shader = RuntimeShader(AGSL_SRC)
    val paint = Paint().apply { shader = this@AgslHolder.shader }
}

private fun newAgsl(): AgslHolder? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslHolder() else null

private val Unexplained = Color(0xFFFF3B5C)
private val ExplainedC = Color(0xFFFFB020)
private val Cyan = Color(0xFF3DE8FF)

@Composable
fun BoxScope.DecodeOverlay(frame: DecodeFrame?, corroboration: Int, onCycle: () -> Unit = {}) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { tick = it }
        }
    }

    val bmp: Bitmap? = remember(frame?.seq) {
        val f = frame
        if (f == null || f.w <= 0 || f.h <= 0 || f.pixels.size < f.w * f.h) null
        else Bitmap.createBitmap(f.pixels, f.w, f.h, Bitmap.Config.ARGB_8888)
    }
    val bmpShader: BitmapShader? = remember(bmp) {
        if (bmp == null) null else BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    val agsl = remember { newAgsl() }
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            color = android.graphics.Color.WHITE
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val t = tick
        val f = frame
        if (f != null && bmp != null) {
            val mw = f.w.toFloat()
            val mh = f.h.toFloat()
            val s = max(size.width / mw, size.height / mh)
            val offX = (size.width - mw * s) / 2f
            val offY = (size.height - mh * s) / 2f

            if (f.status != DecodeStatus.CALIBRATING) {
                if (agsl != null && f.packed && bmpShader != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ) {
                    drawAgsl(agsl, bmpShader, size.width, size.height, mw, mh, t)
                } else {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                        dstSize = IntSize((mw * s).roundToInt(), (mh * s).roundToInt()),
                        filterQuality = FilterQuality.Low
                    )
                }

                // dense independent-flow vectors (only cells the decoder found suspicious)
                val cellPx = mw / f.flowW * s
                var drawn = 0
                for (cy in 0 until f.flowH) {
                    for (cx in 0 until f.flowW) {
                        if (drawn >= 70) break
                        val i = 2 * (cy * f.flowW + cx)
                        val u = f.flow[i]
                        val v = f.flow[i + 1]
                        val mag = sqrt(u * u + v * v)
                        if (mag < 0.35f) continue
                        val ox = offX + (cx + 0.5f) * cellPx
                        val oy = offY + (cy + 0.5f) * cellPx
                        drawLine(
                            Cyan.copy(alpha = 0.75f),
                            Offset(ox, oy),
                            Offset(ox + u * cellPx * 1.2f, oy + v * cellPx * 1.2f),
                            strokeWidth = 1.5f
                        )
                        drawn++
                    }
                }
            }

            // reticles
            textPaint.textSize = 9.sp.toPx()
            for (b in f.blobs.filter { it.confirmed }.take(4)) {
                val col = if (b.cause == Cause.UNEXPLAINED && corroboration > 0) Unexplained else ExplainedC
                val alpha = 1f
                val cx = offX + b.cx * mw * s
                val cy = offY + b.cy * mh * s
                val hw = max(b.halfW * mw * s, 10f) + 4f
                val hh = max(b.halfH * mh * s, 10f) + 4f
                val L = 7f + hw * 0.25f
                val c = col.copy(alpha = alpha)
                val sw = if (b.cause == Cause.UNEXPLAINED) 2.5f else 1.5f
                // four corner brackets
                drawLine(c, Offset(cx - hw, cy - hh), Offset(cx - hw + L, cy - hh), sw)
                drawLine(c, Offset(cx - hw, cy - hh), Offset(cx - hw, cy - hh + L), sw)
                drawLine(c, Offset(cx + hw, cy - hh), Offset(cx + hw - L, cy - hh), sw)
                drawLine(c, Offset(cx + hw, cy - hh), Offset(cx + hw, cy - hh + L), sw)
                drawLine(c, Offset(cx - hw, cy + hh), Offset(cx - hw + L, cy + hh), sw)
                drawLine(c, Offset(cx - hw, cy + hh), Offset(cx - hw, cy + hh - L), sw)
                drawLine(c, Offset(cx + hw, cy + hh), Offset(cx + hw - L, cy + hh), sw)
                drawLine(c, Offset(cx + hw, cy + hh), Offset(cx + hw, cy + hh - L), sw)
                if (b.cause == Cause.UNEXPLAINED && b.confirmed) {
                    drawCircle(
                        c, radius = hw * 0.55f + 3f * ((t / 120_000_000L) % 6).toFloat() * 0.5f,
                        center = Offset(cx, cy), style = Stroke(1.2f)
                    )
                }
                val label = if (b.cause == Cause.UNEXPLAINED) "#${b.id} ${"%.1f".format(b.sigma)}σ"
                else b.cause.label
                textPaint.color = android.graphics.Color.argb(
                    (alpha * 255).toInt(),
                    (col.red * 255).toInt(), (col.green * 255).toInt(), (col.blue * 255).toInt()
                )
                val ty = if (cy - hh - 4f > 12f) cy - hh - 4f else cy + hh + 12f
                drawIntoCanvas { it.nativeCanvas.drawText(label, max(2f, cx - hw), ty, textPaint) }
            }
        }
    }

    // status strip
    val f = frame
    val visualOnly = f?.status == DecodeStatus.UNEXPLAINED && corroboration == 0
    val statusColor = when (f?.status) {
        DecodeStatus.UNEXPLAINED -> if (visualOnly) ExplainedC else Unexplained
        DecodeStatus.EXPLAINED -> ExplainedC
        DecodeStatus.DARK -> Color(0xFF8AA0B8)
        DecodeStatus.CLEAR -> Color(0xFF39E58C)
        DecodeStatus.GATED -> Color(0xFF8AA0B8)
        else -> Cyan
    }
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .clickable { onCycle() }
            .padding(4.dp)
            .background(Color.Black.copy(0.62f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            if (f == null) "DECODE · waiting for camera…"
            else if (visualOnly) "DECODE · VISUAL ONLY · score ${(f.anomalyIndex * 100).toInt()}% · x-chan 0/2"
            else "DECODE · ${f.status.name} · score ${(f.anomalyIndex * 100).toInt()}% · x-chan $corroboration/2",
            color = statusColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace
        )
        if (f != null) {
            Text(
                f.note,
                color = Color(0xFFD7DEE8), fontSize = 8.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                "sens ${listOf("STRICT", "NORMAL", "LOOSE")[f.level.coerceIn(0, 2)]} (tap) · floor ${"%.3f".format(f.noiseFloor)} · ego ${f.egoX},${f.egoY} · glob ${(f.globalActivity * 100).toInt()}%",
                color = Color(0xFF8AA0B8), fontSize = 8.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAgsl(
    h: AgslHolder,
    bitmapShader: BitmapShader,
    vw: Float,
    vh: Float,
    mw: Float,
    mh: Float,
    tNanos: Long
) {
    h.shader.setInputShader("zmap", bitmapShader)
    h.shader.setFloatUniform("viewSize", vw, vh)
    h.shader.setFloatUniform("mapSize", mw, mh)
    h.shader.setFloatUniform("time", ((tNanos % 1_000_000_000_000L) / 1e9f))
    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, vw, vh, h.paint) }
}
