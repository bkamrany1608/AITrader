package com.aitrader

import kotlin.math.abs
import kotlin.math.max

object Ind {
    class AdxR(val adx: DoubleArray, val pdi: DoubleArray, val mdi: DoubleArray)

    fun ema(x: DoubleArray, p: Int): DoubleArray {
        val r = DoubleArray(x.size)
        if (x.isEmpty()) return r
        val k = 2.0 / (p + 1)
        r[0] = x[0]
        for (i in 1 until x.size) r[i] = x[i] * k + r[i - 1] * (1 - k)
        return r
    }

    fun rsi(c: DoubleArray, p: Int = 14): DoubleArray {
        val r = DoubleArray(c.size) { 50.0 }
        if (c.size <= p) return r
        var g = 0.0; var l = 0.0
        for (i in 1..p) { val d = c[i] - c[i - 1]; if (d > 0) g += d else l -= d }
        g /= p; l /= p
        r[p] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
        for (i in p + 1 until c.size) {
            val d = c[i] - c[i - 1]
            g = (g * (p - 1) + max(d, 0.0)) / p
            l = (l * (p - 1) + max(-d, 0.0)) / p
            r[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
        }
        return r
    }

    fun atr(cs: List<Candle>, p: Int = 14): DoubleArray {
        val r = DoubleArray(cs.size)
        if (cs.isEmpty()) return r
        r[0] = cs[0].h - cs[0].l
        for (i in 1 until cs.size) {
            val tr = max(cs[i].h - cs[i].l, max(abs(cs[i].h - cs[i - 1].c), abs(cs[i].l - cs[i - 1].c)))
            r[i] = if (i < p) (r[i - 1] * i + tr) / (i + 1) else (r[i - 1] * (p - 1) + tr) / p
        }
        return r
    }

    fun adx(cs: List<Candle>, p: Int = 14): AdxR {
        val n = cs.size
        val adx = DoubleArray(n); val pdi = DoubleArray(n); val mdi = DoubleArray(n)
        if (n < p * 2 + 2) return AdxR(adx, pdi, mdi)
        val dx = DoubleArray(n)
        var tr = 0.0; var pm = 0.0; var mm = 0.0
        for (i in 1 until n) {
            val up = cs[i].h - cs[i - 1].h
            val dn = cs[i - 1].l - cs[i].l
            val pdm = if (up > dn && up > 0) up else 0.0
            val mdm = if (dn > up && dn > 0) dn else 0.0
            val t = max(cs[i].h - cs[i].l, max(abs(cs[i].h - cs[i - 1].c), abs(cs[i].l - cs[i - 1].c)))
            if (i <= p) { tr += t; pm += pdm; mm += mdm } else { tr = tr - tr / p + t; pm = pm - pm / p + pdm; mm = mm - mm / p + mdm }
            if (i >= p && tr > 0) {
                pdi[i] = 100 * pm / tr; mdi[i] = 100 * mm / tr
                val s = pdi[i] + mdi[i]
                dx[i] = if (s == 0.0) 0.0 else 100 * abs(pdi[i] - mdi[i]) / s
            }
        }
        var a = 0.0
        for (i in p until 2 * p) a += dx[i]
        a /= p
        adx[2 * p - 1] = a
        for (i in 2 * p until n) { a = (a * (p - 1) + dx[i]) / p; adx[i] = a }
        return AdxR(adx, pdi, mdi)
    }
}
