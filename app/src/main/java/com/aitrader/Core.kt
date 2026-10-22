package com.aitrader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Watch(
    val kind: String, val symbol: String, val side: Side, val created: Long, val expiry: Long,
    val ref: Double, val unit: Double, var ext: Double, val peak: Double, val setup: String,
    val regime: String, val leg: Double
)

object Core {
    lateinit var app: Context
    val trades = CopyOnWriteArrayList<Trade>()
    val signals = CopyOnWriteArrayList<Signal>()
    val watches = CopyOnWriteArrayList<Watch>()
    val logs = CopyOnWriteArrayList<String>()
    val tick = MutableStateFlow(0)
    var model = ProbModel()
    var balance = 1000.0; var startBalance = 1000.0
    var dayKey = ""; var dayStartBal = 1000.0; var consec = 0; var pausedUntil = 0L
    var lastScanBoundary = 0L; var lastHeartbeat = System.currentTimeMillis(); var lastDaily = ""; var lastOpt = ""
    var pendingOpt = ""
    @Volatile var running = false
    val prices = HashMap<String, Double>()
    val an = HashMap<String, Analysis>()
    private val anTime = HashMap<String, Long>()
    val htf = HashMap<String, List<Analysis>>()
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        app = ctx; inited = true
        Store.load(ctx); lastDaily = today()
    }

    fun log(m: String) {
        logs.add(0, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + m)
        while (logs.size > 250) logs.removeAt(logs.size - 1)
        tick.value = tick.value + 1
    }
    fun fmt(x: Double) = if (x >= 100) "%.2f".format(x) else if (x >= 1) "%.4f".format(x) else "%.6f".format(x)
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    private fun rollDay() { val d = today(); if (d != dayKey) { dayKey = d; dayStartBal = balance } }

    fun equity(): Double {
        var e = balance
        for (t in trades) if (t.status == "OPEN" && t.mode == "virtual") { val p = prices[t.symbol] ?: continue; e += t.side.sign * (p - t.entry) * t.qty }
        return e
    }

    fun pauseReason(): String? {
        rollDay()
        if (System.currentTimeMillis() < pausedUntil) return "توقف موقت پس از ضررهای پشت‌سرهم"
        if (balance - dayStartBal <= -dayStartBal * Cfg.d("risk.dailyLoss") / 100) return "سقف ضرر روزانه فعال شد"
        return null
    }

    fun canOpen(sym: String, side: Side, kind: String): String? {
        pauseReason()?.let { return it }
        val op = trades.filter { it.status != "CLOSED" && it.mode == "virtual" }
        if (kind != "pyr" && op.size >= Cfg.d("risk.maxOpen").toInt()) return "سقف معاملات باز"
        if ((kind == "base" || kind == "reentry") && op.any { it.symbol == sym && it.side == side && (it.kind == "base" || it.kind == "reentry") }) return "معامله هم‌جهت روی $sym باز است"
        if (kind != "pyr" && op.count { it.side == side } >= Cfg.d("risk.maxSameDir").toInt()) return "سقف معاملات هم‌جهت"
        return null
    }

    fun size(eq: Double, riskK: Double, entry: Double, sl: Double): Pair<Double, Double> {
        val rUsd = eq * Cfg.d("risk.pct") / 100 * riskK
        val loss = rUsd * Cfg.d("risk.maxLossR")
        val cost = entry * (Cfg.d("risk.fee") * 2 + Cfg.d("risk.slip") * 2) / 100
        val per = abs(entry - sl) + cost
        var q = if (per > 0) loss / per else 0.0
        val maxN = eq * Cfg.d("risk.maxLev")
        if (q * entry > maxN) q = maxN / entry
        return Pair(q, rUsd)
    }

    fun mkSignal(sym: String, tf: String, side: Side, price: Double, sl: Double, setup: String, prob: Double, why: List<String>, regime: String): Signal {
        val u = abs(price - sl); val sg = side.sign
        return Signal("X" + java.lang.Long.toString(System.nanoTime(), 36).takeLast(5), sym, tf, side, price, sl,
            listOf(price + sg * 1.5 * u, price + sg * 2.5 * u, price + sg * 4 * u), setup, 0.0, prob, 0.0, regime, System.currentTimeMillis(), why)
    }

    // ---------------------------------------------------------------- باز کردن معامله
    fun open(sig: Signal, riskK: Double, kind: String, parent: String = "", shadow: Boolean = false): Trade? {
        val now = System.currentTimeMillis()
        val (q, r) = size(equity(), riskK, sig.entry, sig.sl)
        if (q <= 0.0 || q.isNaN()) return null
        val p1 = if (sig.manual && sig.tps.isNotEmpty()) abs(sig.tps[0] - sig.entry) else Cfg.d("t.p1AtU") * sig.unit
        val p2 = if (sig.manual && sig.tps.size > 1) abs(sig.tps[1] - sig.entry) else Cfg.d("t.p2AtU") * sig.unit
        val fill = if (sig.limit) sig.entry else sig.entry * (1 + sig.side.sign * Cfg.d("risk.slip") / 100)
        val t = Trade("T" + java.lang.Long.toString(System.nanoTime(), 36), sig.id, sig.symbol, sig.side, fill, sig.sl, sig.tps, q, r, now,
            sig.setup, sig.regime, kind, if (shadow) "shadow" else "virtual", sig.unit, p1, p2)
        t.features = sig.features; t.initProb = sig.prob; t.tf = sig.tf; t.parent = parent; t.lastCheck = now
        t.cautious = kind == "counter"
        if (sig.limit) { t.status = "PENDING"; t.limitPrice = sig.entry; t.expiry = now + 8 * Data.tfMs(sig.tf) }
        trades.add(t)
        if (!shadow && !sig.limit) mirrorOpen(t, riskK)
        Store.save()
        log("${if (shadow) "👻 سایه" else "✅ باز شد"} ${sig.side} ${sig.symbol} [$kind] @${fmt(fill)} SL ${fmt(sig.sl)} qty ${"%.5f".format(q)}")
        return t
    }

    private fun mirrorOpen(v: Trade, riskK: Double) {
        if (!Cfg.b("mode.broker")) return
        val live = Cfg.b("bk.live")
        if (live && !Cfg.b("bk.arm")) { log("⚠️ معامله واقعی: «تأیید نهایی» خاموش است"); return }
        val eq = Broker.balance() ?: run { log("بروکر: دریافت موجودی ناموفق"); return }
        val (q, r) = size(eq, riskK, v.entry, v.sl)
        val fill = Broker.market(v.symbol, v.side, q, false) ?: run { log("بروکر: سفارش رد شد"); return }
        if (v.kind != "pyr") Broker.setStop(v.symbol, v.side, v.sl)
        val m = Trade(v.id + "B", v.signalId, v.symbol, v.side, fill.price, v.sl, v.tps, fill.qty, r, System.currentTimeMillis(), v.setup, v.regime,
            v.kind, if (live) "real" else "demo", v.unit, v.p1Dist, v.p2Dist)
        m.parent = if (v.parent.isNotEmpty()) v.parent + "B" else ""; m.tf = v.tf; m.features = v.features; m.initProb = v.initProb
        m.lastCheck = System.currentTimeMillis()
        trades.add(m)
        log("🏦 بروکر (${m.mode}) ${m.side} ${m.symbol} qty ${fill.qty}")
    }

    // ---------------------------------------------------------------- بستن
    fun closeTrade(t: Trade, price: Double, reason: String, frac: Double) {
        if (t.status != "OPEN") return
        val s = t.side.sign; val now = System.currentTimeMillis()
        val fill = price * (1 - s * Cfg.d("risk.slip") / 100)
        val cq = if (frac >= 0.999) t.qty else min(t.qty, t.origQty * frac)
        val gross = s * (fill - t.entry) * cq
        val fe = (t.entry * cq + fill * cq) * Cfg.d("risk.fee") / 100
        val net = gross - fe
        t.realized += net; t.fees += fe; t.qty -= cq
        if (t.mode == "virtual") balance += net
        if (t.mode == "demo" || t.mode == "real") Broker.market(t.symbol, t.side.opp(), cq, true)
        if (t.qty <= t.origQty * 1e-6) {
            t.status = "CLOSED"; t.closeTime = now; t.exitPrice = fill; t.reason = reason
            if ((t.mode == "demo" || t.mode == "real") && trades.none { it !== t && it.symbol == t.symbol && it.status == "OPEN" && it.mode == t.mode }) Broker.cancelStops(t.symbol)
            onClosed(t)
        } else log("↘️ خروج جزئی ${t.symbol} ($reason) ${"%.2f".format(net)}$")
        Store.save()
    }

    fun manualClose(id: String) {
        val t = trades.firstOrNull { it.id == id } ?: return
        val p = Data.price(t.symbol) ?: prices[t.symbol] ?: return
        if (t.status == "PENDING") { t.status = "CLOSED"; t.reason = "CANCEL"; t.closeTime = System.currentTimeMillis() } else closeTrade(t, p, "MANUAL", 1.0)
        tick.value = tick.value + 1
    }
    fun killSwitch() {
        for (t in trades.filter { it.status != "CLOSED" }) manualClose(t.id)
        pausedUntil = System.currentTimeMillis() + 3600_000; log("🛑 کلید اضطراری: همه معاملات بسته شد، یک ساعت توقف")
    }

    private fun onClosed(t: Trade) {
        val now = System.currentTimeMillis()
        if ((t.mode == "virtual" || t.mode == "shadow") && t.kind == "base") model.learn(t.setup.substringBefore("+"), t.regime, t.features, t.realized > 0)
        if (t.mode == "virtual") {
            if (t.realized < 0) { consec++; if (consec >= Cfg.d("risk.maxConsec").toInt()) { pausedUntil = now + Cfg.d("risk.pauseMin").toLong() * 60_000; consec = 0; log("⏸ توقف پس از ضررهای پشت‌سرهم") } } else consec = 0
            if ((t.reason == "SL" || t.reason == "EARLY") && t.kind != "counter" && Cfg.b("t.counter"))
                watches.add(Watch("COUNTER", t.symbol, t.side.opp(), now, now + 8 * Data.tfMs(t.tf), t.exitPrice, t.unit, t.exitPrice, 0.0, t.setup, t.regime, 0.0))
        }
        log("🏁 بسته شد ${t.symbol} ${t.side} [${t.mode}/${t.kind}] ${t.reason} PnL ${"%.2f".format(t.realized)}$ (${"%.2f".format(t.rMult)}R)")
        if (t.mode != "shadow" && (t.mode != "virtual" || true) && Telegram.enabled() && t.mode == "virtual") {
            Telegram.text("🏁 ${t.symbol} ${t.side} [${t.kind}]\nعلت خروج: ${t.reason}\nسود/زیان: ${"%.2f".format(t.realized)}$ | ${"%.2f".format(t.rMult)}R\nموجودی: ${"%.2f".format(balance)}$")
        }
    }

    // ---------------------------------------------------------------- مدیریت
    private fun refreshAnalysis(sym: String) {
        val now = System.currentTimeMillis()
        if (now - (anTime[sym] ?: 0L) < 60_000 && an.containsKey(sym)) return
        val cs = Data.klines(sym, Cfg.s("scan.tf"), 400)
        if (cs.size >= 120) { an[sym] = Analyzer.analyze(cs); anTime[sym] = now }
        if (!htf.containsKey(sym)) loadHtf(sym)
    }
    private fun loadHtf(sym: String) {
        htf[sym] = Cfg.s("scan.htf").split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull {
            val c = Data.klines(sym, it, 300); if (c.size >= 60) Analyzer.analyze(c) else null
        }
    }

    fun manageOnce() {
        rollDay()
        val open = trades.filter { it.status != "CLOSED" }
        val syms = (open.map { it.symbol } + watches.map { it.symbol }).distinct()
        for (sym in syms) {
            val price = Data.price(sym) ?: continue
            prices[sym] = price
            val k = Data.klines(sym, "1m", 8, false)
            refreshAnalysis(sym)
            for (t in open.filter { it.symbol == sym }.sortedBy { if (it.kind == "pyr") 1 else 0 }) {
                try { manageTrade(t, price, k) } catch (e: Exception) { log("خطای مدیریت ${t.symbol}: ${e.message}") }
            }
            processWatches(sym, price)
        }
        if (open.isNotEmpty()) Store.save()
        tick.value = tick.value + 1
    }

    private fun updateStop(t: Trade) { if ((t.mode == "demo" || t.mode == "real") && t.kind != "pyr") Broker.setStop(t.symbol, t.side, t.sl) }

    private fun manageTrade(t: Trade, price: Double, k: List<Candle>) {
        if (t.status == "CLOSED") return
        val now = System.currentTimeMillis(); val s = t.side.sign
        val since = k.filter { it.t + 60_000 >= t.lastCheck }
        val hiX = max(price, since.maxOfOrNull { it.h } ?: price); val loX = min(price, since.minOfOrNull { it.l } ?: price)
        t.lastCheck = now
        if (t.status == "PENDING") {
            if (now > t.expiry) { t.status = "CLOSED"; t.reason = "EXPIRED"; t.closeTime = now; return }
            val touched = if (t.side == Side.BUY) loX <= t.limitPrice else hiX >= t.limitPrice
            if (touched) { t.status = "OPEN"; t.entry = t.limitPrice; t.openTime = now; if (t.mode == "virtual") mirrorOpen(t, 1.0); log("📍 سفارش لیمیت فعال شد ${t.symbol}") }
            return
        }
        if (t.kind == "pyr") {
            val p = trades.firstOrNull { it.id == t.parent }
            if (p == null || p.status == "CLOSED") { closeTrade(t, price, "PARENT", 1.0); return }
            if ((p.sl - t.sl) * s > 0) t.sl = p.sl
        }
        val advX = if (s > 0) loX else hiX; val favX = if (s > 0) hiX else loX
        if (s * (advX - t.sl) <= 0) {
            closeTrade(t, t.sl, if (s * (t.sl - t.entry) > 0) "TRAIL" else if (t.tp1Hit) "BE" else "SL", 1.0); return
        }
        val fav = s * (price - t.entry)
        t.maxFav = max(t.maxFav, s * (favX - t.entry)); t.maxAdv = max(t.maxAdv, -s * (advX - t.entry))
        val lock = t.entry * (Cfg.d("risk.fee") * 2 + Cfg.d("risk.slip") * 2) / 100

        if (Cfg.b("t.partials")) {
            if (!t.tp1Hit && s * (favX - t.entry) >= t.p1Dist) {
                t.tp1Hit = true; closeTrade(t, t.entry + s * t.p1Dist, "TP1", Cfg.d("t.p1Pct") / 100)
                if (s * (t.entry + s * lock - t.sl) > 0) t.sl = t.entry + s * lock
                updateStop(t)
            }
            if (t.status == "CLOSED") return
            if (!t.tp2Hit && s * (favX - t.entry) >= t.p2Dist) {
                t.tp2Hit = true; closeTrade(t, t.entry + s * t.p2Dist, "TP2", Cfg.d("t.p2Pct") / 100)
                val ns = t.entry + s * 0.5 * t.p1Dist
                if (s * (ns - t.sl) > 0) t.sl = ns
                updateStop(t)
            }
            if (t.status == "CLOSED") return
        }
        if (!Cfg.b("t.trail") && t.tps.isNotEmpty() && s * (favX - t.entry) >= abs(t.tps.last() - t.entry)) { closeTrade(t, t.tps.last(), "TP", 1.0); return }

        val a = an[t.symbol]; val h = htf[t.symbol] ?: emptyList()
        if (Cfg.b("t.trail") && a != null && t.maxFav >= t.unit) {
            val ns = price - s * Cfg.d("t.trailAtr") * a.a
            if (s * (ns - t.sl) > 0 && s * (ns - t.entry) > 0) { t.sl = ns; updateStop(t) }
        }
        val spd = if (a != null && k.size >= 6) s * (price - k[k.size - 6].c) / a.a else 0.0
        val cp = if (a != null) SignalEngine.contProb(t.side, a, h, spd) else 0.5

        val adverse = -s * (price - t.entry) * t.qty
        if (Cfg.b("t.earlyExit") && adverse >= Cfg.d("risk.earlyR") * t.riskUsd && cp * 100 < Cfg.d("t.earlyProbMin")) { closeTrade(t, price, "EARLY", 1.0); return }
        if (Cfg.b("t.corrExit") && t.maxFav >= t.unit) {
            val gb = (t.maxFav - fav) / t.maxFav
            if (gb > Cfg.d("t.corrPct") / 100) {
                val peak = t.entry + s * t.maxFav
                closeTrade(t, price, "CORR", 1.0)
                if (t.mode == "virtual" && t.kind != "counter") {
                    val exp = now + 16 * Data.tfMs(t.tf)
                    if (Cfg.b("t.reentry")) watches.add(Watch("REENTRY", t.symbol, t.side, now, exp, price, t.unit, price, peak, t.setup, t.regime, t.maxFav))
                    if (Cfg.b("t.deepCorr")) watches.add(Watch("DEEP", t.symbol, t.side.opp(), now, exp, price, t.unit, price, peak, t.setup, t.regime, t.maxFav))
                }
                return
            }
        }
        if (fav > 0 && cp < 0.25) { closeTrade(t, price, "REVERSAL", 1.0); return }

        if (t.mode == "virtual" && Cfg.b("t.pyramid") && (t.kind == "base" || t.kind == "reentry") &&
            t.pyrCount < Cfg.d("t.pyrMax").toInt() && fav >= (t.pyrCount + 1) * Cfg.d("t.pyrStepU") * t.unit &&
            cp >= 0.65 && canOpen(t.symbol, t.side, "pyr") == null) {
            val sl2 = if (s * (t.sl - t.entry) > 0) t.sl else t.entry + s * lock
            if (s * (price - sl2) > 0.5 * t.unit) {
                val sig = mkSignal(t.symbol, t.tf, t.side, price, sl2, t.setup + "+PYR", cp, listOf("هرمی پله ${t.pyrCount + 1}"), t.regime)
                t.pyrCount++
                open(sig, Cfg.d("t.pyrRiskK"), "pyr", t.id)
                if (Telegram.enabled()) Telegram.text("🔺 پله هرمی ${t.pyrCount} روی ${t.symbol} ${t.side} @${fmt(price)}")
            }
        }
    }

    private fun processWatches(sym: String, price: Double) {
        val a = an[sym] ?: return; val at = a.a; val now = System.currentTimeMillis()
        for (w in watches.filter { it.symbol == sym }) {
            if (now > w.expiry) { watches.remove(w); continue }
            val orig = if (w.kind == "REENTRY") w.side else w.side.opp()
            if (w.kind != "COUNTER") w.ext = if (orig == Side.BUY) min(w.ext, price) else max(w.ext, price)
            val cp = SignalEngine.contProb(w.side, a, htf[sym] ?: emptyList(), 0.0)
            var sl = 0.0; var rk = 1.0; var kind = ""; var go = false
            when (w.kind) {
                "COUNTER" -> if (w.side.sign * (price - w.ref) >= 0.5 * w.unit && cp >= 0.6) { go = true; sl = w.ref - w.side.sign * 0.2 * w.unit; rk = Cfg.d("t.counterRiskK"); kind = "counter" }
                "REENTRY" -> if (w.side.sign * (price - w.ext) >= 0.6 * at && cp * 100 >= Cfg.d("t.reentryProb")) { go = true; sl = w.ext - w.side.sign * 0.2 * at; kind = "reentry" }
                "DEEP" -> {
                    val depth = orig.sign * (w.peak - w.ext) / max(w.leg, 1e-9)
                    if (depth >= 0.38 && cp >= 0.70 && abs(price - w.ext) <= 0.5 * at) { go = true; sl = price - w.side.sign * at; rk = Cfg.d("t.counterRiskK"); kind = "counter" }
                }
            }
            if (!go || canOpen(sym, w.side, kind) != null) continue
            if (abs(price - sl) < 0.5 * at) sl = price - w.side.sign * 0.5 * at
            val sig = mkSignal(sym, Cfg.s("scan.tf"), w.side, price, sl, w.setup.substringBefore("+") + "|" + w.kind, cp, listOf("ایجنت: ${w.kind}"), a.regime)
            open(sig, rk, kind)
            if (Telegram.enabled()) Telegram.text("🔁 ${w.kind} → ${w.side} ${sym} @${fmt(price)} | SL ${fmt(sl)} | ریسک ×${rk}")
            watches.remove(w)
        }
    }

    // ---------------------------------------------------------------- اسکن
    fun scanOnce() {
        val syms = Cfg.s("scan.symbols").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val tf = Cfg.s("scan.tf"); val notes = ArrayList<String>(); val found = ArrayList<Signal>()
        for (sym in syms) {
            val cs = Data.klines(sym, tf, 500)
            if (cs.size < 220) { log("داده کافی نیست: $sym"); continue }
            val a = Analyzer.analyze(cs); an[sym] = a; anTime[sym] = System.currentTimeMillis()
            loadHtf(sym)
            prices[sym] = cs.last().c
            found += SignalEngine.scan(sym, tf, a, htf[sym] ?: emptyList(), model, notes)
        }
        notes.take(6).forEach { log("↩️ $it") }
        var shadowN = 0
        for (sig in found.sortedByDescending { it.prob * it.quality }) shadowN += decide(sig, shadowN)
        log("اسکن کامل شد: ${syms.size} نماد، ${found.size} کاندید")
        Store.save()
    }

    private fun decide(sig: Signal, shadowN: Int): Int {
        signals.add(sig)
        val n = model.samples(sig.setup, sig.regime)
        val ok = sig.prob * 100 >= Cfg.d("gate.prob") && sig.stat * 100 >= Cfg.d("gate.stat") && sig.quality >= Cfg.d("gate.quality") &&
            sig.rr() >= Cfg.d("gate.minRR") && n >= Cfg.d("gate.warmup")
        if (ok) {
            val why = canOpen(sig.symbol, sig.side, "base")
            if (why != null) { log("⛔ ${sig.symbol} ${sig.side}: $why"); return 0 }
            if (open(sig, 1.0, "base") != null) announce(sig, false)
            return 0
        }
        val miss = "${sig.symbol} ${sig.side} ${sig.setup}: P=${(sig.prob * 100).toInt()}% W=${(sig.stat * 100).toInt()}% Q=${"%.1f".format(sig.quality)} n=$n"
        log("🔎 فرصت رد شد → $miss")
        if (Cfg.b("shadow.on") && sig.quality >= 4 && shadowN < 3 &&
            trades.none { it.status != "CLOSED" && it.symbol == sig.symbol && it.side == sig.side && (it.mode == "shadow" || it.mode == "virtual") }) {
            open(sig, 1.0, "base", "", true)
            if (Cfg.b("tg.shadow")) announce(sig, true)
            return 1
        }
        return 0
    }

    fun signalText(sig: Signal, shadow: Boolean): String {
        val n = model.samples(sig.setup, sig.regime)
        return (if (shadow) "👻 سایه/یادگیری\n" else "📣 سیگنال ${if (sig.side == Side.BUY) "🟢 BUY" else "🔴 SELL"}\n") +
            "${sig.symbol} (${sig.tf}) | ${sig.setup} | ${sig.regime}\n" +
            "ورود: ${fmt(sig.entry)}${if (sig.limit) " (لیمیت)" else ""}\nحد ضرر: ${fmt(sig.sl)}  (حداکثر ضرر ${Cfg.d("risk.maxLossR")}R)\n" +
            sig.tps.mapIndexed { i, t -> "TP${i + 1}: ${fmt(t)}" }.joinToString(" | ") + "\n" +
            "کیفیت ${"%.1f".format(sig.quality)}/10 | احتمال مدل ${(sig.prob * 100).toInt()}% | کران آماری ${(sig.stat * 100).toInt()}% | نمونه $n\n" +
            sig.reasons.joinToString("؛ ")
    }

    private fun announce(sig: Signal, shadow: Boolean) {
        if (!Telegram.enabled()) return
        val txt = signalText(sig, shadow)
        val a = an[sig.symbol]
        if (Cfg.b("tg.chart") && a != null) {
            try { Telegram.photo(ChartRender.png(ChartRender.render(a, sig, 120, title = "${sig.symbol} ${sig.tf}")), txt.take(900)); return } catch (e: Exception) { }
        }
        Telegram.text(txt)
    }

    fun executeManual(sig: Signal): String {
        canOpen(sig.symbol, sig.side, "base")?.let { return "رد شد: $it" }
        val t = open(sig, 1.0, "base") ?: return "اندازه پوزیشن صفر شد (حد ضرر/موجودی را بررسی کنید)"
        signals.add(sig)
        if (Telegram.enabled()) Telegram.text("✍️ سیگنال دستی تأییدشده و اجرا شد\n" + signalText(sig, false))
        return "اجرا شد: ${t.side} ${t.symbol} (${if (t.status == "PENDING") "سفارش لیمیت" else "بازار"})"
    }

    // ---------------------------------------------------------------- دوره‌ای
    fun statusText(): String {
        val op = trades.filter { it.status == "OPEN" && it.mode == "virtual" }
        return "❤️ ایجنت فعال است\nموجودی ${"%.2f".format(balance)}$ | اکوییتی ${"%.2f".format(equity())}$\nمعاملات باز: ${op.size} | امروز: ${"%.2f".format(balance - dayStartBal)}$"
    }

    fun periodic() {
        rollDay()
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(Calendar.HOUR_OF_DAY)
        if (Cfg.b("rep.auto") && Telegram.enabled() && lastDaily != today()) {
            lastDaily = today()
            try { Telegram.doc("daily_${today()}.xlsx", Reports.xlsx("daily"), "گزارش روزانه") } catch (e: Exception) { }
        }
        if (Cfg.b("opt.on") && hour >= Cfg.d("opt.hourUtc").toInt() && lastOpt != today()) { lastOpt = today(); Optimizer.run() }
        if (Cfg.b("tg.heartbeat") && Telegram.enabled() && now - lastHeartbeat > 6 * 3600_000L) { lastHeartbeat = now; Telegram.text(statusText()) }
    }

    fun bootstrapIfNeeded() {
        if (!model.bootstrapped) { log("📚 شروع آموزش اولیه از تاریخچه…"); Optimizer.bootstrap(); model.bootstrapped = true; Store.save() }
    }
}
