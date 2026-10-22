package com.aitrader

import kotlin.math.abs

class Parsed(val symbol: String?, val side: Side?, val entry: Double?, val sl: Double?, val tps: List<Double>)
class Check(val name: String, val level: String, val msg: String)
class Verdict(val checks: List<Check>, val sig: Signal?) { val ok: Boolean get() = sig != null }

object Manual {
    private const val NUM = "(\\d+(?:\\.\\d+)?)"
    private val OPT = RegexOption.IGNORE_CASE

    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s) sb.append(when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰'); in '٠'..'٩' -> '0' + (ch - '٠'); '٫' -> '.'; '،' -> ','; else -> ch
        })
        return sb.toString().replace(Regex("(?<=\\d),(?=\\d{3})"), "")
    }

    fun parse(raw: String): Parsed {
        val t = norm(raw); val low = t.lowercase()
        var sym: String? = null
        Regex("([A-Za-z0-9]{2,10})\\s*[/\\-_ ]?\\s*(USDT|USDC|BUSD|PERP)\\b", OPT).find(t)?.let {
            sym = it.groupValues[1].uppercase() + (if (it.groupValues[2].uppercase() == "PERP") "USDT" else it.groupValues[2].uppercase())
        }
        if (sym == null) Regex("#([A-Za-z]{2,10})").find(t)?.let { sym = it.groupValues[1].uppercase() + "USDT" }
        val bi = listOf("buy", "long", "خرید", "لانگ", "صعودی").map { low.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val si = listOf("sell", "short", "فروش", "شورت", "نزولی").map { low.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val side = if (bi != null && (si == null || bi < si)) Side.BUY else if (si != null) Side.SELL else null
        val entry = Regex("(?:entry|enter|ورود|(?:buy|sell)\\s*(?:at|@|zone|limit)|@)[^0-9\\n]{0,15}$NUM(?:\\s*[-–~]\\s*$NUM)?", OPT).find(t)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDoubleOrNull(); if (b != null) (a + b) / 2 else a
        }
        val sl = Regex("(?:stop\\s*loss|stoploss|\\bsl\\b|stop|حد\\s*ضرر|استاپ(?:\\s*لاس)?)[^0-9\\n]{0,15}$NUM", OPT).find(t)?.groupValues?.get(1)?.toDouble()
        val tps = Regex("(?:take\\s*profit|\\btp|target|تارگت|هدف|حد\\s*سود)\\s*(?:\\d(?!\\d))?[^0-9\\n]{0,15}$NUM", OPT)
            .findAll(t).map { it.groupValues[1].toDouble() }.distinct().toList()
        return Parsed(sym, side, entry, sl, tps)
    }

    fun validate(p: Parsed): Verdict {
        val ch = ArrayList<Check>()
        fun add(n: String, l: String, m: String) { ch.add(Check(n, l, m)) }
        val sym = p.symbol; val side = p.side
        if (sym == null) add("نماد", "fail", "نماد پیدا نشد (مثلاً BTCUSDT)") else add("نماد", "ok", sym)
        if (side == null) add("جهت", "fail", "خرید/فروش مشخص نیست") else add("جهت", "ok", side.name)
        if (p.sl == null) add("حد ضرر", "fail", "حد ضرر ندارد")
        if (p.tps.isEmpty()) add("هدف", "fail", "هدف ندارد")
        if (ch.any { it.level == "fail" } || sym == null || side == null || p.sl == null) return Verdict(ch, null)

        val price = Data.price(sym)
        if (price == null) { add("قیمت", "fail", "قیمت $sym از صرافی دریافت نشد (نماد یا شبکه)"); return Verdict(ch, null) }
        var entry = p.entry ?: price; var limit = false
        val maxDev = Cfg.d("man.maxDevPct")
        val dev = abs(entry - price) / price * 100
        when {
            p.entry == null -> add("ورود", "warn", "قیمت ورود ندارد؛ ورود در بازار ${Core.fmt(price)}")
            dev <= 0.15 -> { entry = price; add("ورود", "ok", "نزدیک قیمت بازار") }
            (side == Side.BUY && entry > price) || (side == Side.SELL && entry < price) -> { entry = price; add("ورود", "warn", "قیمت ورود از قیمت فعلی گذشته؛ ورود بازار ${Core.fmt(price)}") }
            dev > maxDev -> add("ورود", "fail", "فاصله ورود ${"%.2f".format(dev)}٪ از قیمت (سقف ${maxDev}٪)")
            else -> { limit = true; add("ورود", "ok", "سفارش لیمیت ${Core.fmt(entry)} (فاصله ${"%.2f".format(dev)}٪)") }
        }
        val sl: Double = p.sl; val sg = side.sign
        val tps = p.tps.sortedBy { abs(it - entry) }
        if (!(sg * (entry - sl) > 0 && tps.all { sg * (it - entry) > 0 })) add("هندسه", "fail", "حد ضرر/اهداف با جهت معامله سازگار نیست")
        else add("هندسه", "ok", "SL ${Core.fmt(sl)} | TP1 ${Core.fmt(tps[0])}")
        if (sg * (price - sl) <= 0) add("قیمت زنده", "fail", "قیمت فعلی از حد ضرر عبور کرده")
        if (sg * (price - tps[0]) >= 0) add("قیمت زنده", "fail", "هدف اول قبلاً خورده؛ سیگنال کهنه است")
        val rr = abs(tps[0] - entry) / max1(abs(entry - sl))
        if (rr < Cfg.d("man.minRR")) add("RR", "fail", "RR=${"%.2f".format(rr)} کمتر از حداقل ${Cfg.d("man.minRR")}") else add("RR", "ok", "RR=${"%.2f".format(rr)}")

        var regime = "?"; var cp = 0.5
        val tf = Cfg.s("scan.tf")
        val cs = Data.klines(sym, tf, 400)
        if (cs.size < 150) add("تحلیل ایجنت", "warn", "داده کافی برای تحلیل مستقل نیست")
        else {
            val a = Analyzer.analyze(cs); val at = a.a; regime = a.regime
            val htfA = Cfg.s("scan.htf").split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { val c = Data.klines(sym, it, 300); if (c.size >= 60) Analyzer.analyze(c) else null }
            val d = abs(entry - sl)
            when {
                d < 0.3 * at -> add("فاصله SL", "fail", "حد ضرر بسیار تنگ (${"%.2f".format(d / at)} ATR) — نویز آن را می‌زند")
                d > 6 * at -> add("فاصله SL", "fail", "حد ضرر بسیار دور (${"%.1f".format(d / at)} ATR)")
                d < 0.6 * at || d > 4 * at -> add("فاصله SL", "warn", "فاصله SL ${"%.1f".format(d / at)} ATR (غیرمعمول)")
                else -> add("فاصله SL", "ok", "${"%.1f".format(d / at)} ATR")
            }
            cp = SignalEngine.contProb(side, a, htfA, 0.0)
            val pct = (cp * 100).toInt()
            val info = "رژیم ${a.regime} | روند ${a.trend} | احتمال ادامه ${pct}%"
            when {
                cp < 0.40 && Cfg.b("man.needAgree") -> add("نظر ایجنت", "fail", "ایجنت مخالف است — $info")
                cp < 0.50 -> add("نظر ایجنت", "warn", "ایجنت موافق نیست — $info")
                else -> add("نظر ایجنت", "ok", info)
            }
        }
        Core.canOpen(sym, side, "base")?.let { add("ریسک", "fail", it) } ?: add("ریسک", "ok", "سقف‌های ریسک رعایت شده")

        if (ch.any { it.level == "fail" }) return Verdict(ch, null)
        val sig = Signal("M" + java.lang.Long.toString(System.currentTimeMillis(), 36).takeLast(5), sym, tf, side, entry, sl, tps, "MANUAL",
            cp * 10, cp, 0.0, regime, System.currentTimeMillis(), ch.map { "${it.name}: ${it.msg}" }, true, DoubleArray(0), limit)
        return Verdict(ch, sig)
    }
    private fun max1(x: Double) = if (x < 1e-12) 1e-12 else x
}
