package com.aitrader

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max

object Reports {
    class Stats(
        val n: Int, val wins: Int, val net: Double, val netPct: Double, val avgR: Double, val pf: Double,
        val maxDd: Double, val maxDdPct: Double, val best: Double, val worst: Double, val fees: Double,
        val grossW: Double, val grossL: Double, val equity: List<Double>
    ) { val winRate: Double get() = if (n == 0) 0.0 else wins.toDouble() / n }

    fun periodStart(period: String): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return when (period) {
            "daily" -> { c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.timeInMillis }
            "weekly" -> System.currentTimeMillis() - 7 * 86_400_000L
            else -> 0L
        }
    }
    fun select(period: String, mode: String = "virtual"): List<Trade> {
        val st = periodStart(period)
        return Core.trades.filter { it.status == "CLOSED" && it.mode == mode && it.closeTime >= st && it.reason != "EXPIRED" && it.reason != "CANCEL" }.sortedBy { it.closeTime }
    }

    fun stats(trs: List<Trade>): Stats {
        var net = 0.0; var gw = 0.0; var gl = 0.0; var wins = 0; var fees = 0.0; var peak = 0.0; var dd = 0.0
        var best = 0.0; var worst = 0.0; var rs = 0.0
        val eq = ArrayList<Double>(); eq.add(0.0)
        for (t in trs) {
            net += t.realized; fees += t.fees; rs += t.rMult
            if (t.realized > 0) { wins++; gw += t.realized } else gl += -t.realized
            best = max(best, t.realized); worst = minOf(worst, t.realized)
            peak = max(peak, net); dd = max(dd, peak - net); eq.add(net)
        }
        val base = Core.startBalance
        return Stats(trs.size, wins, net, net / base * 100, if (trs.isEmpty()) 0.0 else rs / trs.size,
            if (gl == 0.0) (if (gw > 0) 99.0 else 0.0) else gw / gl, dd, dd / base * 100, best, worst, fees, gw, gl, eq)
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0
    private fun group(trs: List<Trade>, key: (Trade) -> String): List<List<Any?>> {
        val rows = ArrayList<List<Any?>>(); rows.add(listOf("گروه", "تعداد", "برد٪", "سود خالص $", "میانگین R", "PF"))
        for ((k, g) in trs.groupBy(key)) { val s = stats(g); rows.add(listOf(k, s.n, r2(s.winRate * 100), r2(s.net), r2(s.avgR), r2(s.pf))) }
        return rows
    }

    fun xlsx(period: String): ByteArray {
        val v = select(period); val s = stats(v)
        val sum = listOf(
            listOf("گزارش", period), listOf("تعداد معاملات", s.n), listOf("نرخ برد %", r2(s.winRate * 100)),
            listOf("سود خالص $", r2(s.net)), listOf("سود خالص % از موجودی اولیه", r2(s.netPct)), listOf("میانگین R", r2(s.avgR)),
            listOf("Profit Factor", r2(s.pf)), listOf("حداکثر دراوداون $", r2(s.maxDd)), listOf("حداکثر دراوداون %", r2(s.maxDdPct)),
            listOf("بهترین معامله $", r2(s.best)), listOf("بدترین معامله $", r2(s.worst)), listOf("کارمزد کل $", r2(s.fees)),
            listOf("موجودی فعلی $", r2(Core.balance)), listOf("موجودی اولیه $", r2(Core.startBalance))
        )
        val head = listOf("زمان باز", "زمان بسته", "نماد", "جهت", "نوع", "ستاپ", "رژیم", "ورود", "خروج", "حجم اولیه", "سود $", "R", "کارمزد", "MFE (U)", "MAE (U)", "علت خروج", "احتمال اولیه")
        val tr = ArrayList<List<Any?>>(); tr.add(head)
        for (t in v) tr.add(listOf(Date(t.openTime).toString(), Date(t.closeTime).toString(), t.symbol, t.side.name, t.kind, t.setup, t.regime, t.entry, t.exitPrice,
            t.origQty, r2(t.realized), r2(t.rMult), r2(t.fees), r2(t.maxFav / t.unit), r2(t.maxAdv / t.unit), t.reason, r2(t.initProb * 100)))
        val eq = ArrayList<List<Any?>>(); eq.add(listOf("شماره معامله", "سود تجمعی $")); s.equity.forEachIndexed { i, e -> eq.add(listOf(i, r2(e))) }
        val cal = ArrayList<List<Any?>>(); cal.add(listOf("بازه احتمال پیش‌بینی", "تعداد", "میانگین پیش‌بینی%", "برد واقعی%"))
        val sh = (select(period, "virtual") + select(period, "shadow")).filter { it.kind == "base" && it.initProb > 0 }
        for ((lo, hi) in listOf(0.0 to 0.5, 0.5 to 0.6, 0.6 to 0.7, 0.7 to 0.8, 0.8 to 1.01)) {
            val g = sh.filter { it.initProb >= lo && it.initProb < hi }
            if (g.isNotEmpty()) cal.add(listOf("${(lo * 100).toInt()}-${(minOf(hi, 1.0) * 100).toInt()}", g.size, r2(g.map { it.initProb }.average() * 100), r2(g.count { it.realized > 0 } * 100.0 / g.size)))
        }
        return Xlsx.write(listOf(
            "Summary" to sum, "Trades" to tr, "BySetup" to group(v) { it.setup }, "BySymbol" to group(v) { it.symbol },
            "ByReason" to group(v) { it.reason }, "ByKind" to group(v) { it.kind }, "Calibration" to cal,
            "Shadow" to group(select(period, "shadow")) { it.setup }, "Equity" to eq
        ))
    }

    fun saveDownload(ctx: Context, name: String, mime: String, bytes: ByteArray): String? = try {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AITrader")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) null else { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; "Downloads/AITrader/$name" }
    } catch (e: Exception) { null }
}

object Xlsx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun col(i: Int): String { var n = i; var s = ""; do { s = ('A' + n % 26) + s; n = n / 26 - 1 } while (n >= 0); return s }

    fun write(sheets: List<Pair<String, List<List<Any?>>>>): ByteArray {
        val bo = ByteArrayOutputStream(); val z = ZipOutputStream(bo)
        fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        put("[Content_Types].xml", xml + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>")
        put("_rels/.rels", xml + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        put("xl/workbook.xml", xml + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            sheets.mapIndexed { i, s -> "<sheet name=\"${esc(s.first.take(30))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
        put("xl/_rels/workbook.xml.rels", xml + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } + "</Relationships>")
        sheets.forEachIndexed { si, sh ->
            val sb = StringBuilder(xml + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            sh.second.forEachIndexed { r, row ->
                sb.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v ->
                    val ref = col(c) + (r + 1)
                    when (v) {
                        null -> {}
                        is Number -> if (v.toDouble().isNaN() || v.toDouble().isInfinite()) {} else sb.append("<c r=\"$ref\"><v>$v</v></c>")
                        else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(v.toString())}</t></is></c>")
                    }
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
        }
        z.close()
        return bo.toByteArray()
    }
}
