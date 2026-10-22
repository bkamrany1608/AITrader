package com.aitrader

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

class ProbModel {
    companion object { const val NF = 10 }
    val stats = HashMap<String, IntArray>()
    var w = DoubleArray(NF).also { it[NF - 1] = -0.2 }
    var bootstrapped = false

    fun key(s: String, r: String) = "$s|$r"
    fun samples(s: String, r: String) = stats[key(s, r)]?.get(1) ?: 0
    private fun sg(x: Double) = 1.0 / (1.0 + exp(-x))
    fun lg(f: DoubleArray): Double {
        if (f.size != NF) return 0.45
        var z = 0.0
        for (i in 0 until NF) z += w[i] * f[i]
        return sg(z)
    }
    fun prior(q: Double) = 0.35 + 0.03 * q

    fun prob(s: String, r: String, f: DoubleArray, q: Double): Double {
        val st = stats[key(s, r)]
        val n = st?.get(1) ?: 0; val wins = st?.get(0) ?: 0
        val pr = prior(q); val l = lg(f)
        val emp = (wins + 6 * pr) / (n + 6.0)
        val wt = n / (n + 25.0)
        return ((1 - wt) * (0.6 * pr + 0.4 * l) + wt * (0.65 * emp + 0.35 * l)).coerceIn(0.01, 0.99)
    }

    /** کران پایین Wilson (z=1.28 ≈ ۹۰٪ یک‌طرفه) برای نرخ برد تجربی ستاپ/رژیم */
    fun wilson(s: String, r: String): Double {
        val st = stats[key(s, r)] ?: return 0.0
        val n = st[1]; if (n == 0) return 0.0
        val p = st[0].toDouble() / n; val z = 1.28
        return ((p + z * z / (2 * n) - z * sqrt((p * (1 - p) + z * z / (4 * n)) / n)) / (1 + z * z / n)).coerceIn(0.0, 1.0)
    }

    fun learn(s: String, r: String, f: DoubleArray, win: Boolean) {
        val st = stats.getOrPut(key(s, r)) { IntArray(2) }
        if (win) st[0]++
        st[1]++
        if (f.size == NF && Cfg.b("l.enabled")) {
            val g = (if (win) 1.0 else 0.0) - lg(f)
            val lr = Cfg.d("l.lr")
            for (i in 0 until NF) w[i] += lr * g * f[i]
        }
    }

    fun toJson(): JSONObject {
        val j = JSONObject()
        val st = JSONObject()
        for ((k, v) in stats) st.put(k, JSONArray(listOf(v[0], v[1])))
        j.put("stats", st).put("w", JSONArray(w.toList())).put("boot", bootstrapped)
        return j
    }

}

fun probFromJson(j: JSONObject): ProbModel {
    val m = ProbModel()
    val st = j.optJSONObject("stats")
    if (st != null) for (k in st.keys()) { val a = st.getJSONArray(k); m.stats[k] = intArrayOf(a.getInt(0), a.getInt(1)) }
    val w = j.optJSONArray("w")
    if (w != null && w.length() == ProbModel.NF) m.w = DoubleArray(ProbModel.NF) { w.getDouble(it) }
    m.bootstrapped = j.optBoolean("boot")
    return m
}
