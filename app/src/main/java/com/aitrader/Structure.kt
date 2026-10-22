package com.aitrader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Swing(val i: Int, val p: Double, val high: Boolean)
data class Level(val p: Double, val touches: Int, val strong: Boolean, val lastIdx: Int)
data class Zone(val lo: Double, val hi: Double, val bull: Boolean, val i: Int, val kind: String)
data class TLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val support: Boolean) {
    fun at(i: Int): Double = p1 + (p2 - p1) * (i - i1) / (i2 - i1).toDouble()
}

class Analysis(
    val cs: List<Candle>, val swings: List<Swing>, val levels: List<Level>, val trend: Int, val regime: String,
    val ema21: DoubleArray, val ema50: DoubleArray, val ema200: DoubleArray, val atr: DoubleArray,
    val rsi: DoubleArray, val adx: Ind.AdxR, val lines: List<TLine>, val zones: List<Zone>,
    val bos: Int, val choch: Int, val sweep: Int, val sweepPrice: Double,
    val poc: Double, val vaHi: Double, val vaLo: Double, val volAvg: Double
) {
    val last: Int get() = cs.size - 1
    val price: Double get() = cs.last().c
    val a: Double get() = atr[cs.size - 1]
}

object Analyzer {
    fun swings(cs: List<Candle>, k: Int): List<Swing> {
        val r = ArrayList<Swing>()
        for (i in k until cs.size - k) {
            var hi = true; var lo = true
            for (j in 1..k) {
                if (cs[i].h <= cs[i - j].h || cs[i].h < cs[i + j].h) hi = false
                if (cs[i].l >= cs[i - j].l || cs[i].l > cs[i + j].l) lo = false
            }
            if (hi) r.add(Swing(i, cs[i].h, true))
            if (lo) r.add(Swing(i, cs[i].l, false))
        }
        return r
    }

    private fun levels(sw: List<Swing>, a: Double): List<Level> {
        val ps = sw.sortedBy { it.p }
        val out = ArrayList<Level>()
        var grp = ArrayList<Swing>()
        fun flush() {
            if (grp.isNotEmpty()) out.add(Level(grp.map { it.p }.average(), grp.size, grp.size >= 3, grp.maxOf { it.i }))
            grp = ArrayList()
        }
        for (s in ps) { if (grp.isNotEmpty() && s.p - grp.last().p > a * 0.6) flush(); grp.add(s) }
        flush()
        return out
    }

    private fun trendline(cs: List<Candle>, pts: List<Swing>, support: Boolean, a: Double): TLine? {
        if (pts.size < 2) return null
        val last = pts.last()
        var best: TLine? = null; var bestT = -1
        for (p1 in pts.dropLast(1).takeLast(3)) {
            if (last.i - p1.i < 5) continue
            if (support && last.p <= p1.p) continue
            if (!support && last.p >= p1.p) continue
            val ln = TLine(p1.i, p1.p, last.i, last.p, support)
            var ok = true; var touches = 2
            for (i in p1.i..cs.size - 1) {
                val lv = ln.at(i)
                if (support) { if (cs[i].c < lv - 0.2 * a) { ok = false; break }; if (i > last.i && abs(cs[i].l - lv) < 0.25 * a) touches++ }
                else { if (cs[i].c > lv + 0.2 * a) { ok = false; break }; if (i > last.i && abs(cs[i].h - lv) < 0.25 * a) touches++ }
            }
            if (ok && touches > bestT) { best = ln; bestT = touches }
        }
        return best
    }

    private fun orderBlock(cs: List<Candle>, hs: List<Swing>, ls: List<Swing>, bull: Boolean): Zone? {
        val n = cs.size
        val src = if (bull) hs else ls
        for (h in src.reversed()) {
            var k = -1
            for (j in h.i + 1 until n) { if (if (bull) cs[j].c > h.p else cs[j].c < h.p) { k = j; break } }
            if (k < 0 || n - 1 - k > 80) continue
            var m = h.i + 1
            for (j in h.i + 1..k) { if (if (bull) cs[j].l < cs[m].l else cs[j].h > cs[m].h) m = j }
            var x = -1
            for (j in min(k - 1, m + 2) downTo max(0, m - 2)) {
                if (if (bull) cs[j].c < cs[j].o else cs[j].c > cs[j].o) { x = j; break }
            }
            if (x < 0) continue
            val lo = cs[x].l; val hi = cs[x].h
            var mitig = false
            for (j in k + 1 until n) if (if (bull) cs[j].c < lo else cs[j].c > hi) { mitig = true; break }
            if (mitig) continue
            return Zone(lo, hi, bull, x, "OB")
        }
        return null
    }

    fun analyze(cs: List<Candle>): Analysis {
        val n = cs.size
        val c = DoubleArray(n) { cs[it].c }
        val e21 = Ind.ema(c, 21); val e50 = Ind.ema(c, 50); val e200 = Ind.ema(c, 200)
        val atr = Ind.atr(cs); val rsi = Ind.rsi(c); val adx = Ind.adx(cs)
        val a = atr[n - 1]
        val sw = swings(cs, 3)
        val hs = sw.filter { it.high }; val ls = sw.filter { !it.high }
        val levels = if (Cfg.b("m.sr")) levels(sw.filter { it.i > n - 300 }, a) else emptyList()
        val trend = if (hs.size >= 2 && ls.size >= 2) {
            val hh = hs[hs.size - 1].p > hs[hs.size - 2].p
            val hl = ls[ls.size - 1].p > ls[ls.size - 2].p
            if (hh && hl) 1 else if (!hh && !hl) -1 else 0
        } else 0

        var bos = 0; var choch = 0; var sweep = 0; var sweepP = 0.0
        val zones = ArrayList<Zone>()
        if (Cfg.b("m.smc") && n > 30) {
            val hp = hs.lastOrNull { it.i <= n - 5 }
            val lp = ls.lastOrNull { it.i <= n - 5 }
            if (hp != null && (n - 3 until n).any { cs[it].c > hp.p } && cs[n - 4].c <= hp.p) bos = 1
            if (lp != null && (n - 3 until n).any { cs[it].c < lp.p } && cs[n - 4].c >= lp.p) bos = -1
            choch = if (bos == 1 && trend == -1) 1 else if (bos == -1 && trend == 1) -1 else 0
            orderBlock(cs, hs, ls, true)?.let { zones.add(it) }
            orderBlock(cs, hs, ls, false)?.let { zones.add(it) }
            val fv = ArrayList<Zone>()
            for (i in max(2, n - 60) until n) {
                if (cs[i - 2].h < cs[i].l) {
                    val lo = cs[i - 2].h; val hi = cs[i].l
                    if ((i + 1 until n).none { cs[it].l <= lo } && hi - lo > 0.2 * a) fv.add(Zone(lo, hi, true, i, "FVG"))
                } else if (cs[i - 2].l > cs[i].h) {
                    val hi = cs[i - 2].l; val lo = cs[i].h
                    if ((i + 1 until n).none { cs[it].h >= hi } && hi - lo > 0.2 * a) fv.add(Zone(lo, hi, false, i, "FVG"))
                }
            }
            zones.addAll(fv.takeLast(4))
            for (b in n - 3 until n) {
                for (h in hs) if (h.i < n - 6 && h.i > n - 100 && cs[b].h > h.p && cs[b].c < h.p) { sweep = -1; sweepP = cs[b].h }
                for (l in ls) if (l.i < n - 6 && l.i > n - 100 && cs[b].l < l.p && cs[b].c > l.p) { sweep = 1; sweepP = cs[b].l }
            }
        }

        val lines = ArrayList<TLine>()
        if (Cfg.b("m.trendlines")) {
            trendline(cs, ls, true, a)?.let { lines.add(it) }
            trendline(cs, hs, false, a)?.let { lines.add(it) }
        }

        var poc = 0.0; var vaHi = 0.0; var vaLo = 0.0
        if (Cfg.b("m.volprofile") && n >= 50) {
            val w = cs.takeLast(min(200, n))
            val lo = w.minOf { it.l }; val hi = w.maxOf { it.h }
            val bins = 40; val step = (hi - lo) / bins
            if (step > 0) {
                val vol = DoubleArray(bins)
                for (x in w) { val tp = (x.h + x.l + x.c) / 3; vol[min(bins - 1, ((tp - lo) / step).toInt())] += x.v }
                var pi = 0
                for (i in 0 until bins) if (vol[i] > vol[pi]) pi = i
                val tot = vol.sum(); var acc = vol[pi]; var l = pi; var h = pi
                while (acc < 0.7 * tot && (l > 0 || h < bins - 1)) {
                    val dn = if (l > 0) vol[l - 1] else -1.0
                    val up = if (h < bins - 1) vol[h + 1] else -1.0
                    if (up >= dn) { h++; acc += vol[h] } else { l--; acc += vol[l] }
                }
                poc = lo + (pi + 0.5) * step; vaLo = lo + l * step; vaHi = lo + (h + 1) * step
            }
        }

        val atrAvg = atr.takeLast(100).average()
        val regime = if (!Cfg.b("m.regime")) "OFF" else {
            val ad = adx.adx[n - 1]
            when {
                a > 1.8 * atrAvg -> "VOLATILE"
                ad >= 25 && adx.pdi[n - 1] > adx.mdi[n - 1] && e21[n - 1] > e50[n - 1] -> "TREND_UP"
                ad >= 25 && adx.mdi[n - 1] > adx.pdi[n - 1] && e21[n - 1] < e50[n - 1] -> "TREND_DOWN"
                else -> "RANGE"
            }
        }
        val volAvg = cs.takeLast(20).map { it.v }.average()
        return Analysis(cs, sw, levels, trend, regime, e21, e50, e200, atr, rsi, adx, lines, zones, bos, choch, sweep, sweepP, poc, vaHi, vaLo, volAvg)
    }
}
