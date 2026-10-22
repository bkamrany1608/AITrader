package com.aitrader

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

enum class Side {
    BUY, SELL;
    val sign: Double get() = if (this == BUY) 1.0 else -1.0
    fun opp() = if (this == BUY) SELL else BUY
}

data class Signal(
    val id: String, val symbol: String, val tf: String, val side: Side,
    val entry: Double, val sl: Double, val tps: List<Double>,
    val setup: String, val quality: Double, val prob: Double, val stat: Double,
    val regime: String, val time: Long, val reasons: List<String>,
    val manual: Boolean = false, val features: DoubleArray = DoubleArray(0),
    val limit: Boolean = false
) {
    val unit: Double get() = abs(entry - sl)
    fun rr(): Double = if (unit == 0.0 || tps.isEmpty()) 0.0 else abs(tps[0] - entry) / unit
    fun toJson(): JSONObject = JSONObject().put("id", id).put("symbol", symbol).put("tf", tf).put("side", side.name)
        .put("entry", entry).put("sl", sl).put("tps", JSONArray(tps)).put("setup", setup)
        .put("quality", quality).put("prob", prob).put("stat", stat).put("regime", regime)
        .put("time", time).put("reasons", JSONArray(reasons)).put("manual", manual)
}

class Trade(
    val id: String, val signalId: String, val symbol: String, val side: Side,
    var entry: Double, var sl: Double, val tps: List<Double>, var qty: Double,
    val riskUsd: Double, var openTime: Long, val setup: String, val regime: String,
    val kind: String, val mode: String, val unit: Double,
    val p1Dist: Double, val p2Dist: Double
) {
    var status = "OPEN"
    var closeTime = 0L
    var exitPrice = 0.0
    var realized = 0.0
    var fees = 0.0
    var maxFav = 0.0
    var maxAdv = 0.0
    var origQty = qty
    var tp1Hit = false
    var tp2Hit = false
    var reason = ""
    var features = DoubleArray(0)
    var initProb = 0.0
    var limitPrice = 0.0
    var expiry = 0L
    var lastCheck = 0L
    var pyrCount = 0
    var parent = ""
    var tf = "15m"
    var cautious = false
    val rMult: Double get() = if (riskUsd == 0.0) 0.0 else realized / riskUsd

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("signalId", signalId); put("symbol", symbol); put("side", side.name)
        put("entry", entry); put("sl", sl); put("tps", JSONArray(tps)); put("qty", qty)
        put("riskUsd", riskUsd); put("openTime", openTime); put("setup", setup); put("regime", regime)
        put("kind", kind); put("mode", mode); put("unit", unit); put("p1", p1Dist); put("p2", p2Dist)
        put("status", status); put("closeTime", closeTime); put("exit", exitPrice); put("realized", realized)
        put("fees", fees); put("maxFav", maxFav); put("maxAdv", maxAdv); put("origQty", origQty)
        put("tp1", tp1Hit); put("tp2", tp2Hit); put("reason", reason)
        put("feat", JSONArray(features.toList())); put("prob", initProb); put("limit", limitPrice)
        put("expiry", expiry); put("last", lastCheck); put("pyr", pyrCount); put("parent", parent)
        put("tf", tf); put("caut", cautious)
    }

    companion object {
        fun fromJson(j: JSONObject): Trade {
            val tp = j.getJSONArray("tps")
            val l = (0 until tp.length()).map { tp.getDouble(it) }
            val t = Trade(
                j.getString("id"), j.getString("signalId"), j.getString("symbol"), Side.valueOf(j.getString("side")),
                j.getDouble("entry"), j.getDouble("sl"), l, j.getDouble("qty"), j.getDouble("riskUsd"),
                j.getLong("openTime"), j.getString("setup"), j.getString("regime"), j.getString("kind"),
                j.getString("mode"), j.getDouble("unit"), j.getDouble("p1"), j.getDouble("p2")
            )
            t.status = j.optString("status", "OPEN"); t.closeTime = j.optLong("closeTime"); t.exitPrice = j.optDouble("exit")
            t.realized = j.optDouble("realized"); t.fees = j.optDouble("fees"); t.maxFav = j.optDouble("maxFav")
            t.maxAdv = j.optDouble("maxAdv"); t.origQty = j.optDouble("origQty"); t.tp1Hit = j.optBoolean("tp1")
            t.tp2Hit = j.optBoolean("tp2"); t.reason = j.optString("reason")
            val f = j.optJSONArray("feat")
            t.features = if (f == null) DoubleArray(0) else DoubleArray(f.length()) { f.getDouble(it) }
            t.initProb = j.optDouble("prob"); t.limitPrice = j.optDouble("limit"); t.expiry = j.optLong("expiry")
            t.lastCheck = j.optLong("last"); t.pyrCount = j.optInt("pyr"); t.parent = j.optString("parent")
            t.tf = j.optString("tf", "15m"); t.cautious = j.optBoolean("caut")
            return t
        }
    }
}
