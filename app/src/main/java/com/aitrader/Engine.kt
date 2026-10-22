package com.aitrader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SignalEngine {
    class Cand(val side: Side, val setup: String, val sl: Double, val base: Double, val reasons: MutableList<String>)
    private var seq = 0

    private fun pin(c: Candle, bull: Boolean): Boolean {
        val r = c.h - c.l; if (r <= 0) return false
        val body = abs(c.c - c.o); val lw = min(c.o, c.c) - c.l; val uw = c.h - max(c.o, c.c)
        return if (bull) lw >= 2 * body && lw >= 0.55 * r && c.c >= c.l + 0.6 * r
        else uw >= 2 * body && uw >= 0.55 * r && c.c <= c.l + 0.4 * r
    }
    private fun engulf(p: Candle, c: Candle, bull: Boolean): Boolean =
        if (bull) p.c < p.o && c.c > c.o && c.c >= p.o && c.o <= p.c else p.c > p.o && c.c < c.o && c.c <= p.o && c.o >= p.c

    fun candidates(a: Analysis): List<Cand> {
        val out = ArrayList<Cand>()
        val cs = a.cs; val n = cs.size; val L = n - 1
        if (n < 60) return out
        val p = a.price; val at = a.a; val cur = cs[L]; val prev = cs[L - 1]
        val pat = Cfg.b("m.patterns")
        fun react(bull: Boolean): Boolean {
            val dir = (cur.c - cur.o) * (if (bull) 1 else -1) > 0
            return if (pat) pin(cur, bull) || engulf(prev, cur, bull) || (dir && (cur.c - prev.c) * (if (bull) 1 else -1) > 0) else dir
        }
        for (side in Side.values()) {
            val sg = side.sign; val bull = sg > 0
            val lo3 = min(cur.l, min(prev.l, cs[L - 2].l)); val hi3 = max(cur.h, max(prev.h, cs[L - 2].h))
            val slBase = if (bull) lo3 - 0.3 * at else hi3 + 0.3 * at

            if (Cfg.b("s.pullback")) {
                val trendOk = a.trend == sg.toInt() && (a.regime == "OFF" || (a.regime == "TREND_UP" && bull) || (a.regime == "TREND_DOWN" && !bull))
                if (trendOk) {
                    val nearEma = abs(p - a.ema21[L]) <= 0.7 * at || abs(p - a.ema50[L]) <= 0.5 * at
                    val inZone = a.zones.any { it.bull == bull && p >= it.lo - 0.2 * at && p <= it.hi + 0.2 * at }
                    val lvl = a.levels.any { abs(p - it.p) <= 0.5 * at }
                    val rsiOk = a.rsi[L] in 35.0..65.0
                    if ((nearEma || inZone || lvl) && react(bull) && rsiOk) {
                        val r = mutableListOf("پولبک در روند ${if (bull) "صعودی" else "نزولی"}")
                        if (nearEma) r.add("برخورد با EMA21/50"); if (inZone) r.add("داخل OB/FVG"); if (lvl) r.add("روی سطح S/R")
                        out.add(Cand(side, "PULLBACK", slBase, 4.0 + (if (inZone) 1 else 0) + (if (lvl) 1 else 0), r))
                    }
                }
            }
            if (Cfg.b("s.sweep") && a.sweep == sg.toInt() && (cur.c - cur.o) * sg > 0) {
                val sl = a.sweepPrice - sg * 0.2 * at
                val r = mutableListOf("شکار نقدینگی ${if (bull) "کف" else "سقف"} و بازگشت")
                if (a.choch == sg.toInt()) r.add("CHoCH تأییدکننده")
                out.add(Cand(side, "SWEEP", sl, 4.5 + (if (a.choch == sg.toInt()) 1 else 0), r))
            }
            if (Cfg.b("s.breakout") && a.adx.adx[L] > 20) {
                for (lv in a.levels) {
                    if (lv.touches < 2) continue
                    val broke = (n - 3 until n).any { b -> if (bull) cs[b - 1].c <= lv.p && cs[b].c > lv.p else cs[b - 1].c >= lv.p && cs[b].c < lv.p }
                    val retest = if (bull) p > lv.p && p - lv.p <= 0.8 * at else p < lv.p && lv.p - p <= 0.8 * at
                    val vol = (n - 3 until n).any { cs[it].v >= 1.3 * a.volAvg }
                    if (broke && retest && vol && react(bull)) {
                        val sl = if (bull) min(lv.p - 0.6 * at, lo3 - 0.2 * at) else max(lv.p + 0.6 * at, hi3 + 0.2 * at)
                        out.add(Cand(side, "BREAKOUT", sl, 4.0 + (if (lv.strong) 1 else 0), mutableListOf("شکست سطح ${"%.4f".format(lv.p)} با حجم و ریتست")))
                        break
                    }
                }
            }
            if (Cfg.b("s.range") && a.regime == "RANGE") {
                val w = cs.takeLast(40); val rh = w.maxOf { it.h }; val rl = w.minOf { it.l }
                if (rh - rl >= 3 * at) {
                    val atEdge = if (bull) p <= rl + 0.6 * at && a.rsi[L] < 38 else p >= rh - 0.6 * at && a.rsi[L] > 62
                    if (atEdge && react(bull)) {
                        val sl = if (bull) min(rl, lo3) - 0.4 * at else max(rh, hi3) + 0.4 * at
                        out.add(Cand(side, "RANGE", sl, 3.5, mutableListOf("لبه رنج + RSI + کندل برگشتی")))
                    }
                }
            }
            if (Cfg.b("s.obfvg") && (a.bos == sg.toInt() || a.trend == sg.toInt()) && (cur.c - cur.o) * sg > 0) {
                val z = a.zones.firstOrNull { it.bull == bull && p >= it.lo - 0.1 * at && p <= it.hi + 0.1 * at }
                if (z != null) {
                    val overlap = a.zones.any { it !== z && it.bull == bull && it.lo <= z.hi && it.hi >= z.lo }
                    val sl = if (bull) min(z.lo, lo3) - 0.2 * at else max(z.hi, hi3) + 0.2 * at
                    out.add(Cand(side, "OBFVG", sl, 4.0 + (if (overlap) 1 else 0), mutableListOf("واکنش به ${z.kind} پس از ساختار")))
                }
            }
        }
        return out
    }

    private fun build(sym: String, tf: String, a: Analysis, htf: List<Analysis>, cd: Cand, model: ProbModel, notes: MutableList<String>): Signal? {
        val cs = a.cs; val L = cs.size - 1; val entry = a.price; val at = a.a
        val sg = cd.side.sign; val bull = sg > 0
        var sl = cd.sl
        var d = abs(entry - sl)
        if (d < 0.8 * at) { sl = entry - sg * 0.8 * at; d = 0.8 * at }
        if (d > 3.5 * at) { notes.add("$sym ${cd.setup}: حد ضرر خیلی دور"); return null }
        val u = d
        val visual = Cfg.b("m.visual")
        if (visual) {
            val cur = cs[L]
            if (cur.h - cur.l > 3 * at) { notes.add("$sym ${cd.setup}: کندل غول‌پیکر (تعقیب قیمت)"); return null }
            if (abs(entry - a.ema21[L]) > 2.8 * at && cd.setup != "RANGE") { notes.add("$sym ${cd.setup}: قیمت از EMA21 کشیده است"); return null }
            var flips = 0
            for (i in L - 9..L) if ((cs[i].c > cs[i].o) != (cs[i - 1].c > cs[i - 1].o)) flips++
            if (flips > 7 && cd.setup != "RANGE") { notes.add("$sym ${cd.setup}: بازار نویزی"); return null }
        }
        var tp1 = 1.5 * u
        val blocker = a.levels.filter { (it.strong || it.touches >= 2) && sg * (it.p - entry) > 0 }.minByOrNull { sg * (it.p - entry) }
        if (blocker != null) {
            val room = sg * (blocker.p - entry)
            tp1 = min(tp1, room - 0.1 * at)
            if (tp1 < 1.2 * u) { if (visual) { notes.add("$sym ${cd.setup}: فضای کافی تا مقاومت/حمایت بعدی نیست"); return null } else tp1 = 1.5 * u }
        }
        val tps = listOf(entry + sg * tp1, entry + sg * max(2.5 * u, tp1 * 1.4), entry + sg * max(4.0 * u, tp1 * 2.2))

        var q = cd.base; var conf = 0
        var htfAlign = 0.0
        if (Cfg.b("m.mtf") && htf.isNotEmpty()) {
            htfAlign = htf.sumOf { if (it.trend == sg.toInt()) 1 else if (it.trend == -sg.toInt()) -1 else 0 }.toDouble() / htf.size
            q += 2.5 * htfAlign
            cd.reasons.add(if (htfAlign > 0) "هم‌جهت با تایم‌فریم‌های بالاتر" else if (htfAlign < 0) "خلاف تایم‌فریم بالاتر" else "تایم‌فریم بالاتر خنثی")
        }
        val volR = cs[L].v / max(a.volAvg, 1e-9)
        if (volR >= 1.3) { q += 0.7; conf++; cd.reasons.add("حجم بالاتر از میانگین") }
        val rsi = a.rsi[L]
        if ((bull && rsi < 70) || (!bull && rsi > 30)) q += 0.4 else q -= 0.8
        if (a.levels.any { it.strong && sg * (entry - it.p) > 0 && sg * (entry - it.p) < 0.8 * at }) { q += 1; conf++; cd.reasons.add("حمایت/مقاومت قوی پشت معامله") }
        if (Cfg.b("m.patterns") && (pin(cs[L], bull) || engulf(cs[L - 1], cs[L], bull))) { q += 0.8; conf++; cd.reasons.add("الگوی کندلی تأییدی") }
        if (a.zones.any { it.bull == bull && entry >= it.lo - 0.2 * at && entry <= it.hi + 0.2 * at }) { q += 1; conf++ }
        if (a.lines.any { it.support == bull && abs(entry - it.at(L)) < 0.4 * at }) { q += 1; conf++; cd.reasons.add("برخورد با خط روند معتبر") }
        if (a.poc > 0 && abs(entry - a.poc) < 0.5 * at) { q += 0.5; cd.reasons.add("نزدیک POC") }
        val trendReg = a.regime == "TREND_UP" || a.regime == "TREND_DOWN"
        if ((cd.setup == "RANGE" && a.regime == "RANGE") || (cd.setup != "RANGE" && cd.setup != "SWEEP" && trendReg)) q += 0.7
        q = q.coerceIn(0.0, 10.0)

        val f = doubleArrayOf(
            q / 10, htfAlign, (a.adx.adx[L] / 50).coerceIn(0.0, 1.0), (rsi - 50) / 50 * sg, min(volR, 3.0) / 3,
            min(tp1 / u, 4.0) / 4, (at / entry * 100).coerceIn(0.0, 3.0) / 3, if (a.trend == sg.toInt()) 1.0 else 0.0,
            min(conf, 5) / 5.0, 1.0
        )
        val prob = model.prob(cd.setup, a.regime, f, q)
        val stat = model.wilson(cd.setup, a.regime)
        seq++
        return Signal("S" + java.lang.Long.toString(System.currentTimeMillis(), 36).takeLast(5) + seq, sym, tf, cd.side, entry, sl, tps,
            cd.setup, q, prob, stat, a.regime, System.currentTimeMillis(), cd.reasons, false, f, false)
    }

    fun scan(sym: String, tf: String, a: Analysis, htf: List<Analysis>, model: ProbModel, notes: MutableList<String>): List<Signal> =
        candidates(a).mapNotNull { build(sym, tf, a, htf, it, model, notes) }

    /** احتمال ادامه حرکت در جهت معامله بر اساس وضعیت لحظه‌ای (برای خروج زودهنگام، هرم، ورود مجدد) */
    fun contProb(side: Side, a: Analysis, htf: List<Analysis>, speed: Double = 0.0): Double {
        val s = side.sign; val si = s.toInt(); val L = a.cs.size - 1
        var p = 0.5
        if (a.trend == si) p += 0.10 else if (a.trend == -si) p -= 0.10
        if ((a.regime == "TREND_UP" && s > 0) || (a.regime == "TREND_DOWN" && s < 0)) p += 0.08
        else if ((a.regime == "TREND_UP" && s < 0) || (a.regime == "TREND_DOWN" && s > 0)) p -= 0.08
        if (L >= 4) {
            p += ((a.ema21[L] - a.ema21[L - 3]) / a.a * s).coerceIn(-0.1, 0.1)
            p += ((a.cs[L].c - a.cs[L - 3].c) / a.a * s * 0.04).coerceIn(-0.1, 0.1)
        }
        if (a.choch == -si || a.bos == -si) p -= 0.2
        if (a.sweep == -si) p -= 0.1
        val r = a.rsi[L]
        if ((s > 0 && r > 78) || (s < 0 && r < 22)) p -= 0.08
        for (h in htf) { if (h.trend == si) p += 0.04 else if (h.trend == -si) p -= 0.04 }
        a.levels.filter { it.strong && s * (it.p - a.price) > 0 }.minByOrNull { s * (it.p - a.price) }?.let {
            if (s * (it.p - a.price) < 0.8 * a.a) p -= 0.1
        }
        if (speed < -0.5) p -= 0.15 else if (speed > 0.5) p += 0.05
        return p.coerceIn(0.05, 0.95)
    }
}
