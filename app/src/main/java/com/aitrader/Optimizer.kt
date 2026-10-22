package com.aitrader

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Optimizer {
    class Params(var quality: Double, var trailAtr: Double, var p1: Double, var corr: Double) {
        fun copy() = Params(quality, trailAtr, p1, corr)
    }
    class BtSig(val idx: Int, val sig: Signal)
    class Res(val u: Double, val exit: Int, val tp1First: Boolean)
    class Data1(val cs: List<Candle>, val sigs: List<BtSig>, val atr: DoubleArray)

    fun resample(cs: List<Candle>, f: Int): List<Candle> {
        val out = ArrayList<Candle>(); var end = cs.size
        while (end - f >= 0) {
            val g = cs.subList(end - f, end)
            out.add(Candle(g.first().t, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.sumOf { it.v })); end -= f
        }
        return out.reversed()
    }

    /** برای هر کندل (با گام) بهترین سیگنال موتور را روی تاریخچه جمع می‌کند (HTFها با بازنمونه‌گیری ساخته می‌شوند). */
    fun collect(cs: List<Candle>, step: Int = 3, start: Int = 600): List<BtSig> {
        val dummy = ProbModel(); val res = ArrayList<BtSig>(); val notes = ArrayList<String>()
        var i = start
        while (i < cs.size - 30) {
            val w = cs.subList(max(0, i - 599), i + 1)
            val a = Analyzer.analyze(w)
            val h = listOf(4, 16).map { resample(w, it) }.filter { it.size >= 30 }.map { Analyzer.analyze(it) }
            SignalEngine.scan("BT", "bt", a, h, dummy, notes).maxByOrNull { it.quality }?.let { res.add(BtSig(i, it)) }
            i += step
        }
        return res
    }

    fun simulate(cs: List<Candle>, atr: DoubleArray, b: BtSig, p: Params): Res {
        val sig = b.sig; val s = sig.side.sign; val e = sig.entry; val u = sig.unit; var sl = sig.sl
        var rem = 1.0; var res = 0.0; var tp1 = false; var tp2 = false; var maxFav = 0.0; var first = false
        val cost = (Cfg.d("risk.fee") * 2 + Cfg.d("risk.slip") * 2) / 100 * e / u
        val end = min(cs.size - 1, b.idx + 120)
        for (j in b.idx + 1..end) {
            val c = cs[j]
            val adv = if (s > 0) c.l else c.h; val fv = if (s > 0) c.h else c.l
            if (s * (adv - sl) <= 0) { res += rem * s * (sl - e) / u; return Res(res - cost, j, first) }
            maxFav = max(maxFav, s * (fv - e) / u)
            if (!tp1 && s * (fv - e) >= p.p1 * u) { tp1 = true; first = true; res += 0.3 * p.p1; rem -= 0.3; if (s * (e - sl) > 0) sl = e }
            if (!tp2 && s * (fv - e) >= 2.0 * u) { tp2 = true; res += 0.3 * 2.0; rem -= 0.3; val ns = e + s * 0.5 * u; if (s * (ns - sl) > 0) sl = ns }
            if (maxFav >= 1.0) { val ns = c.c - s * p.trailAtr * atr[j]; if (s * (ns - sl) > 0 && s * (ns - e) > 0) sl = ns }
            val cur = s * (c.c - e) / u
            if (maxFav >= 1.0 && (maxFav - cur) / maxFav > p.corr / 100) { res += rem * cur; return Res(res - cost, j, first) }
        }
        res += rem * s * (cs[end].c - e) / u
        return Res(res - cost, end, first)
    }

    class Score(val n: Int, val sum: Double, val dd: Double, val score: Double, val winRate: Double)

    fun evaluate(d: List<Data1>, p: Params): Score {
        var n = 0; var sum = 0.0; var peak = 0.0; var dd = 0.0; var wins = 0
        for (x in d) {
            var lastExit = -1
            for (b in x.sigs) {
                if (b.idx <= lastExit || b.sig.quality < p.quality) continue
                val r = simulate(x.cs, x.atr, b, p); lastExit = r.exit
                n++; sum += r.u; if (r.u > 0) wins++; peak = max(peak, sum); dd = max(dd, peak - sum)
            }
        }
        val sc = if (n < 15) -1e9 else sum - 0.7 * dd
        return Score(n, sum, dd, sc, if (n > 0) wins.toDouble() / n else 0.0)
    }

    private fun load(limit: Int): List<Data1> {
        val tf = Cfg.s("scan.tf"); val out = ArrayList<Data1>()
        for (sym in Cfg.s("scan.symbols").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }) {
            val cs = Data.klines(sym, tf, limit)
            if (cs.size < 700) { Core.log("تاریخچه کافی نیست: $sym (${cs.size})"); continue }
            out.add(Data1(cs, collect(cs), Ind.atr(cs)))
            Core.log("📈 تاریخچه $sym: ${out.last().sigs.size} کاندید")
        }
        return out
    }

    fun bootstrap() {
        val d = load(1000)
        val base = Params(max(4.0, Cfg.d("gate.quality") - 1.5), Cfg.d("t.trailAtr"), Cfg.d("t.p1AtU"), Cfg.d("t.corrPct"))
        val ev = ArrayList<Triple<Int, Signal, Boolean>>()
        for (x in d) {
            var lastExit = -1
            for (b in x.sigs) {
                if (b.idx <= lastExit || b.sig.quality < base.quality) continue
                val r = simulate(x.cs, x.atr, b, base); lastExit = r.exit
                ev.add(Triple(r.exit, b.sig, r.tp1First))
            }
        }
        for ((_, s, w) in ev.sortedBy { it.first }) Core.model.learn(s.setup, s.regime, s.features, w)
        Core.log("📚 آموزش اولیه: ${ev.size} نمونه از تاریخچه")
    }

    fun run(): String {
        Core.log("🔧 بهینه‌سازی پارامترها شروع شد")
        val d = load(1000)
        if (d.isEmpty()) return "داده کافی نبود"
        var cur = Params(Cfg.d("gate.quality"), Cfg.d("t.trailAtr"), Cfg.d("t.p1AtU"), Cfg.d("t.corrPct"))
        var best = evaluate(d, cur); val first = best
        val minGain = Cfg.d("opt.minGain") / 100
        for (iter in 0 until 25) {
            var bp: Params? = null; var bs = best
            for (k in 0 until 8) {
                val c = cur.copy()
                when (k) {
                    0 -> c.quality = min(9.0, c.quality + 0.5); 1 -> c.quality = max(3.0, c.quality - 0.5)
                    2 -> c.trailAtr = min(4.0, c.trailAtr + 0.25); 3 -> c.trailAtr = max(1.0, c.trailAtr - 0.25)
                    4 -> c.p1 = min(2.0, c.p1 + 0.25); 5 -> c.p1 = max(0.5, c.p1 - 0.25)
                    6 -> c.corr = min(40.0, c.corr + 2.5); 7 -> c.corr = max(10.0, c.corr - 2.5)
                }
                val sc = evaluate(d, c)
                if (sc.score > bs.score) { bs = sc; bp = c }
            }
            if (bp == null) break
            val gain = (bs.score - best.score) / max(abs(best.score), 1.0)
            cur = bp; best = bs
            if (gain < minGain) break
        }
        val j = JSONObject().put("gate.quality", cur.quality).put("t.trailAtr", cur.trailAtr).put("t.p1AtU", cur.p1).put("t.corrPct", cur.corr)
        val msg = "بهینه‌سازی: n=${best.n} برد ${(best.winRate * 100).toInt()}% جمع ${"%.1f".format(best.sum)}U DD ${"%.1f".format(best.dd)}U " +
            "(قبل: ${"%.1f".format(first.sum)}U) → کیفیت ${cur.quality} تریل ${cur.trailAtr} پله۱ ${cur.p1} اصلاح ${cur.corr}%"
        if (best.score > first.score) {
            if (Cfg.b("opt.auto")) { apply(j.toString()); Core.log("✅ $msg (اعمال شد)") }
            else { Core.pendingOpt = j.toString(); Core.log("💡 $msg — برای اعمال از تب تنظیمات بزنید") }
        } else Core.log("ℹ️ بهینه‌سازی: بهبودی پیدا نشد؛ تنظیمات فعلی حفظ شد")
        if (Telegram.enabled()) Telegram.text(msg)
        Store.save()
        return msg
    }

    fun apply(json: String) {
        val j = JSONObject(json)
        for (k in j.keys()) Cfg.set(k, j.get(k).toString())
        Core.pendingOpt = ""; Store.save()
    }
}
