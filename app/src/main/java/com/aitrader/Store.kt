package com.aitrader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Store {
    private fun f(n: String) = File(Core.app.filesDir, n)

    @Synchronized fun save() {
        try {
            f("trades.json").writeText(JSONArray(Core.trades.map { it.toJson() }).toString())
            f("signals.json").writeText(JSONArray(Core.signals.takeLast(300).map { it.toJson() }).toString())
            f("model.json").writeText(Core.model.toJson().toString())
            f("state.json").writeText(JSONObject()
                .put("balance", Core.balance).put("start", Core.startBalance).put("dayKey", Core.dayKey)
                .put("dayStart", Core.dayStartBal).put("consec", Core.consec).put("paused", Core.pausedUntil)
                .put("opt", Core.pendingOpt).toString())
        } catch (e: Exception) { }
    }

    fun load(ctx: Context) {
        try {
            if (f("trades.json").exists()) {
                val a = JSONArray(f("trades.json").readText())
                for (i in 0 until a.length()) Core.trades.add(Trade.fromJson(a.getJSONObject(i)))
            }
            if (f("model.json").exists()) Core.model = probFromJson(JSONObject(f("model.json").readText()))
            if (f("state.json").exists()) {
                val j = JSONObject(f("state.json").readText())
                Core.balance = j.optDouble("balance", Cfg.d("acc.balance")); Core.startBalance = j.optDouble("start", Core.balance)
                Core.dayKey = j.optString("dayKey"); Core.dayStartBal = j.optDouble("dayStart", Core.balance)
                Core.consec = j.optInt("consec"); Core.pausedUntil = j.optLong("paused"); Core.pendingOpt = j.optString("opt")
            } else { Core.balance = Cfg.d("acc.balance"); Core.startBalance = Core.balance; Core.dayStartBal = Core.balance }
        } catch (e: Exception) { }
    }

    fun reset() {
        Core.trades.clear(); Core.signals.clear(); Core.model = ProbModel()
        Core.balance = Cfg.d("acc.balance"); Core.startBalance = Core.balance; Core.dayStartBal = Core.balance
        Core.consec = 0; Core.pausedUntil = 0; Core.watches.clear(); save()
    }
}
