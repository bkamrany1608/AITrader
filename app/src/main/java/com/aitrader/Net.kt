package com.aitrader

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object Net {
    @Volatile private var cl: OkHttpClient? = null
    @Volatile private var pk = "#"
    fun client(): OkHttpClient {
        val p = Cfg.s("net.proxy").trim()
        if (cl == null || p != pk) {
            val b = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            if (p.contains(":")) {
                val po = p.substringAfterLast(":").toIntOrNull()
                if (po != null) b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(p.substringBeforeLast(":"), po)))
            }
            cl = b.build(); pk = p
        }
        return cl!!
    }
    fun get(url: String): String? = try {
        client().newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) { null }
    fun send(req: Request): String? = try {
        client().newCall(req).execute().use { it.body?.string() }
    } catch (e: Exception) { null }
}

object Data {
    fun tfMs(tf: String): Long = when (tf) {
        "1m" -> 60_000L; "5m" -> 300_000L; "15m" -> 900_000L; "30m" -> 1_800_000L
        "1h" -> 3_600_000L; "4h" -> 14_400_000L; "1d" -> 86_400_000L; else -> 900_000L
    }

    private fun f(a: JSONArray, i: Int) = a.get(i).toString().toDouble()

    private fun binance(sym: String, tf: String, limit: Int): List<Candle>? {
        for (host in listOf("https://api.binance.com", "https://data-api.binance.vision")) {
            val s = Net.get("$host/api/v3/klines?symbol=$sym&interval=$tf&limit=${minOf(limit, 1000)}") ?: continue
            val a = JSONArray(s)
            return (0 until a.length()).map { val k = a.getJSONArray(it); Candle(k.getLong(0), f(k, 1), f(k, 2), f(k, 3), f(k, 4), f(k, 5)) }
        }
        return null
    }
    private fun bybit(sym: String, tf: String, limit: Int): List<Candle>? {
        val iv = mapOf("1m" to "1", "5m" to "5", "15m" to "15", "30m" to "30", "1h" to "60", "4h" to "240", "1d" to "D")[tf] ?: return null
        val s = Net.get("https://api.bybit.com/v5/market/kline?category=linear&symbol=$sym&interval=$iv&limit=${minOf(limit, 1000)}") ?: return null
        val a = JSONObject(s).getJSONObject("result").getJSONArray("list")
        return (0 until a.length()).map { val k = a.getJSONArray(it); Candle(k.getString(0).toLong(), f(k, 1), f(k, 2), f(k, 3), f(k, 4), f(k, 5)) }.reversed()
    }
    private fun okx(sym: String, tf: String, limit: Int): List<Candle>? {
        val bar = mapOf("1m" to "1m", "5m" to "5m", "15m" to "15m", "30m" to "30m", "1h" to "1H", "4h" to "4H", "1d" to "1D")[tf] ?: return null
        val inst = sym.removeSuffix("USDT") + "-USDT"
        val s = Net.get("https://www.okx.com/api/v5/market/candles?instId=$inst&bar=$bar&limit=${minOf(limit, 300)}") ?: return null
        val a = JSONObject(s).getJSONArray("data")
        return (0 until a.length()).map { val k = a.getJSONArray(it); Candle(k.getString(0).toLong(), f(k, 1), f(k, 2), f(k, 3), f(k, 4), f(k, 5)) }.reversed()
    }

    fun klines(sym: String, tf: String, limit: Int = 500, closedOnly: Boolean = true): List<Candle> {
        for (src in Cfg.s("data.sources").split(",").map { it.trim().lowercase() }) {
            val r = try {
                when (src) { "binance" -> binance(sym, tf, limit); "bybit" -> bybit(sym, tf, limit); "okx" -> okx(sym, tf, limit); else -> null }
            } catch (e: Exception) { null }
            if (r != null && r.size > 5) return if (closedOnly) r.filter { it.t + tfMs(tf) <= System.currentTimeMillis() } else r
        }
        return emptyList()
    }

    fun price(sym: String): Double? {
        for (src in Cfg.s("data.sources").split(",").map { it.trim().lowercase() }) {
            try {
                when (src) {
                    "binance" -> Net.get("https://api.binance.com/api/v3/ticker/price?symbol=$sym")?.let { return JSONObject(it).getString("price").toDouble() }
                    "bybit" -> Net.get("https://api.bybit.com/v5/market/tickers?category=linear&symbol=$sym")?.let {
                        return JSONObject(it).getJSONObject("result").getJSONArray("list").getJSONObject(0).getString("lastPrice").toDouble() }
                    "okx" -> Net.get("https://www.okx.com/api/v5/market/ticker?instId=${sym.removeSuffix("USDT")}-USDT")?.let {
                        return JSONObject(it).getJSONArray("data").getJSONObject(0).getString("last").toDouble() }
                }
            } catch (e: Exception) { }
        }
        return null
    }
}
