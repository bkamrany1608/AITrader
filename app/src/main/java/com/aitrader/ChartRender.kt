package com.aitrader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object ChartRender {
    fun png(bm: Bitmap): ByteArray { val o = ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.PNG, 90, o); return o.toByteArray() }

    fun render(a: Analysis, sig: Signal?, bars: Int = 120, w: Int = 1080, h: Int = 720, title: String = ""): Bitmap {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(0xFF10141C.toInt())
        val cs = a.cs; val n = cs.size; val from = max(0, n - bars); val vis = cs.subList(from, n)
        var hi = vis.maxOf { it.h }; var lo = vis.minOf { it.l }
        sig?.let { hi = max(hi, max(it.sl, it.tps.maxOrNull() ?: hi)); lo = min(lo, min(it.sl, it.tps.minOrNull() ?: lo)) }
        val pad = (hi - lo) * 0.05; hi += pad; lo -= pad
        val left = 10f; val right = w - 150f; val top = 40f; val bot = h - 20f
        fun x(i: Int) = left + (i - from + 0.5f) * (right - left) / vis.size
        fun y(p: Double) = (top + (hi - p) / (hi - lo) * (bot - top)).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.textSize = 22f
        val bw = (right - left) / vis.size * 0.6f

        if (Cfg.b("draw.zones")) for (z in a.zones) {
            p.style = Paint.Style.FILL; p.color = if (z.bull) 0x3328C76F else 0x33E74C3C
            c.drawRect(max(left, x(z.i)), y(z.hi), right, y(z.lo), p)
            p.color = 0xFFBBBBBB.toInt(); c.drawText(z.kind, right + 6, y((z.hi + z.lo) / 2), p)
        }
        if (Cfg.b("draw.levels")) for (l in a.levels) {
            if (l.p < lo || l.p > hi) continue
            p.style = Paint.Style.STROKE; p.strokeWidth = if (l.strong) 3f else 1.5f
            p.color = if (l.strong) 0xFFF1C40F.toInt() else 0x66F1C40F
            p.pathEffect = if (l.strong) null else DashPathEffect(floatArrayOf(10f, 8f), 0f)
            c.drawLine(left, y(l.p), right, y(l.p), p); p.pathEffect = null
        }
        if (Cfg.b("draw.profile") && a.poc > 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0xFF9B59B6.toInt()
            c.drawLine(left, y(a.poc), right, y(a.poc), p); c.drawText("POC", right + 6, y(a.poc), p)
            p.pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
            c.drawLine(left, y(a.vaHi), right, y(a.vaHi), p); c.drawLine(left, y(a.vaLo), right, y(a.vaLo), p); p.pathEffect = null
        }
        for (i in from until n) {
            val k = cs[i]; val up = k.c >= k.o
            p.color = if (up) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            c.drawLine(x(i), y(k.h), x(i), y(k.l), p)
            p.style = Paint.Style.FILL
            c.drawRect(x(i) - bw / 2, y(max(k.o, k.c)), x(i) + bw / 2, max(y(min(k.o, k.c)), y(max(k.o, k.c)) + 2f), p)
        }
        if (Cfg.b("draw.ema")) {
            for ((arr, col) in listOf(a.ema21 to 0xFF3498DB.toInt(), a.ema50 to 0xFFE67E22.toInt())) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = col
                for (i in from + 1 until n) c.drawLine(x(i - 1), y(arr[i - 1]), x(i), y(arr[i]), p)
            }
        }
        if (Cfg.b("draw.lines")) for (l in a.lines) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = if (l.support) 0xFF1ABC9C.toInt() else 0xFFFF6B81.toInt()
            val i0 = max(from, l.i1)
            c.drawLine(x(i0), y(l.at(i0)), x(n - 1), y(l.at(n - 1)), p)
        }
        sig?.let {
            fun ln(pr: Double, col: Int, t: String) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = col
                p.pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
                c.drawLine(left, y(pr), right, y(pr), p); p.pathEffect = null
                p.style = Paint.Style.FILL; c.drawText("$t ${"%.5g".format(pr)}", right + 6, y(pr) + 8, p)
            }
            ln(it.entry, 0xFFFFFFFF.toInt(), "E"); ln(it.sl, 0xFFFF4D4D.toInt(), "SL")
            it.tps.forEachIndexed { i, t -> ln(t, 0xFF4DFF88.toInt(), "TP${i + 1}") }
        }
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); p.textSize = 26f
        c.drawText(title.ifBlank { "chart" } + "  ${a.regime}", 14f, 28f, p)
        return bm
    }
}
