package com.aitrader

import okhttp3.MultipartBody
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Binance USD-M Futures (حالت One-way). Testnet وقتی bk.live خاموش است. */
object Broker {
    data class Fill(val price: Double, val qty: Double)
    private class Filt(val step: BigDecimal, val minQty: BigDecimal, val tick: BigDecimal)
    private val filt = HashMap<String, Filt>()

    private fun base() = if (Cfg.b("bk.live")) "https://fapi.binance.com" else "https://testnet.binancefuture.com"
    private fun sign(q: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(Cfg.s("bk.secret").toByteArray(), "HmacSHA256"))
        return m.doFinal(q.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun call(method: String, path: String, params: Map<String, String>): String? {
        val all = params + mapOf("timestamp" to System.currentTimeMillis().toString(), "recvWindow" to "10000")
        val q = all.entries.joinToString("&") { "${it.key}=${it.value}" }
        val rb = Request.Builder().url("${base()}$path?$q&signature=${sign(q)}").header("X-MBX-APIKEY", Cfg.s("bk.key"))
        when (method) { "POST" -> rb.post("".toRequestBody()); "DELETE" -> rb.delete(); else -> rb.get() }
        return Net.send(rb.build())
    }

    private fun filters(sym: String): Filt? {
        filt[sym]?.let { return it }
        val s = Net.get("${base()}/fapi/v1/exchangeInfo") ?: return null
        val arr = JSONObject(s).getJSONArray("symbols")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            var step = BigDecimal("0.001"); var minQ = BigDecimal("0.001"); var tick = BigDecimal("0.01")
            val fl = o.getJSONArray("filters")
            for (j in 0 until fl.length()) {
                val f = fl.getJSONObject(j)
                if (f.getString("filterType") == "LOT_SIZE") { step = BigDecimal(f.getString("stepSize")); minQ = BigDecimal(f.getString("minQty")) }
                if (f.getString("filterType") == "PRICE_FILTER") tick = BigDecimal(f.getString("tickSize"))
            }
            filt[o.getString("symbol")] = Filt(step, minQ, tick)
        }
        return filt[sym]
    }
    private fun roundDown(v: Double, unit: BigDecimal): String {
        val q = BigDecimal(v).divide(unit, 0, RoundingMode.DOWN).multiply(unit)
        return q.setScale(maxOf(0, unit.stripTrailingZeros().scale()), RoundingMode.DOWN).toPlainString()
    }

    fun balance(): Double? = try {
        val a = JSONArray(call("GET", "/fapi/v2/balance", emptyMap()) ?: "")
        var r: Double? = null
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("asset") == "USDT") r = a.getJSONObject(i).getString("balance").toDouble()
        r
    } catch (e: Exception) { null }

    fun market(sym: String, side: Side, qty: Double, reduce: Boolean): Fill? = try {
        val f = filters(sym) ?: return null
        val qs = roundDown(qty, f.step)
        if (BigDecimal(qs) < f.minQty) null else {
            val p = mutableMapOf("symbol" to sym, "side" to side.name, "type" to "MARKET", "quantity" to qs, "newOrderRespType" to "RESULT")
            if (reduce) p["reduceOnly"] = "true"
            val j = JSONObject(call("POST", "/fapi/v1/order", p) ?: "")
            if (!j.has("orderId")) null else {
                var ap = j.optString("avgPrice", "0").toDoubleOrNull() ?: 0.0
                if (ap <= 0) ap = Data.price(sym) ?: 0.0
                Fill(ap, qs.toDouble())
            }
        }
    } catch (e: Exception) { null }

    fun setStop(sym: String, posSide: Side, stop: Double) {
        try {
            val f = filters(sym) ?: return
            call("DELETE", "/fapi/v1/allOpenOrders", mapOf("symbol" to sym))
            call("POST", "/fapi/v1/order", mapOf("symbol" to sym, "side" to posSide.opp().name, "type" to "STOP_MARKET",
                "stopPrice" to roundDown(stop, f.tick), "closePosition" to "true", "workingType" to "MARK_PRICE"))
        } catch (e: Exception) { }
    }
    fun cancelStops(sym: String) { try { call("DELETE", "/fapi/v1/allOpenOrders", mapOf("symbol" to sym)) } catch (e: Exception) { } }
    fun test(): String = balance()?.let { "اتصال موفق — موجودی USDT: $it" } ?: "اتصال ناموفق (کلید/پروکسی/Testnet را بررسی کنید)"
}

object Telegram {
    private fun base() = Cfg.s("tg.api").ifBlank { "https://api.telegram.org" }.trimEnd('/') + "/bot" + Cfg.s("tg.token")
    fun enabled() = Cfg.b("tg.on") && Cfg.s("tg.token").isNotBlank() && Cfg.s("tg.chat").isNotBlank()

    fun text(msg: String): Boolean {
        if (!enabled()) return false
        val body = FormBody.Builder().add("chat_id", Cfg.s("tg.chat")).add("text", msg.take(4000)).build()
        return Net.send(Request.Builder().url(base() + "/sendMessage").post(body).build())?.contains("\"ok\":true") == true
    }
    fun photo(bytes: ByteArray, caption: String): Boolean {
        if (!enabled()) return false
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chat_id", Cfg.s("tg.chat"))
            .addFormDataPart("caption", caption.take(1000))
            .addFormDataPart("photo", "chart.png", bytes.toRequestBody("image/png".toMediaType())).build()
        return Net.send(Request.Builder().url(base() + "/sendPhoto").post(mb).build())?.contains("\"ok\":true") == true
    }
    fun doc(name: String, bytes: ByteArray, caption: String): Boolean {
        if (!enabled()) return false
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("chat_id", Cfg.s("tg.chat"))
            .addFormDataPart("caption", caption.take(1000))
            .addFormDataPart("document", name, bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        return Net.send(Request.Builder().url(base() + "/sendDocument").post(mb).build())?.contains("\"ok\":true") == true
    }
}
