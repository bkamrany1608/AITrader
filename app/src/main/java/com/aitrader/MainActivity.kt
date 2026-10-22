package com.aitrader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { AppRoot() }
                }
            }
        }
    }
}

private fun f2(x: Double) = "%.2f".format(x)

@Composable
fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("داشبورد" to "🏠", "چارت" to "📈", "سیگنال دستی" to "✍️", "معاملات" to "📒", "گزارش" to "📊", "تنظیمات" to "⚙️")
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(t.second) }, label = { Text(t.first, fontSize = 9.sp, maxLines = 1) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> Dashboard(); 1 -> ChartScreen(); 2 -> ManualScreen(); 3 -> TradesScreen(); 4 -> ReportsScreen(); else -> SettingsScreen() }
        }
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun Dashboard() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val tk = Core.tick.collectAsState().value
    Page {
        Text("ایجنت ترید هوشمند", style = MaterialTheme.typography.titleLarge)
        Text("موجودی ${f2(Core.balance)}$ | اکوییتی ${f2(Core.equity())}$ | امروز ${f2(Core.balance - Core.dayStartBal)}$  [$tk]", fontSize = 13.sp)
        Text("وضعیت: ${if (Core.running) "🟢 در حال اجرا" else "🔴 متوقف"} | مدل: ${if (Core.model.bootstrapped) "آموزش‌دیده از تاریخچه" else "بدون آموزش اولیه"}", fontSize = 13.sp)
        Core.pauseReason()?.let { Text("⛔ $it", color = Color(0xFFFF6B6B)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { ContextCompat.startForegroundService(ctx, Intent(ctx, AgentService::class.java)) }) { Text("شروع") }
            Button(onClick = { ctx.stopService(Intent(ctx, AgentService::class.java)) }) { Text("توقف") }
            Button(onClick = { scope.launch(Dispatchers.IO) { try { Core.scanOnce() } catch (e: Exception) { Core.log("خطا: ${e.message}") } } }) { Text("اسکن") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { Core.killSwitch() } }) { Text("🛑 کلید اضطراری") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("🔋 بهینه‌سازی باتری") }
        }
        Text("معاملات باز", style = MaterialTheme.typography.titleMedium)
        val open = Core.trades.filter { it.status != "CLOSED" }
        if (open.isEmpty()) Text("—")
        for (t in open) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                val p = Core.prices[t.symbol] ?: t.entry
                val pnl = t.realized + t.side.sign * (p - t.entry) * t.qty
                Text("${t.symbol} ${t.side} [${t.mode}/${t.kind}] ${t.status}", fontSize = 14.sp)
                Text("ورود ${Core.fmt(t.entry)} | الان ${Core.fmt(p)} | SL ${Core.fmt(t.sl)}", fontSize = 12.sp)
                Text("PnL ${f2(pnl)}$ (${f2(if (t.riskUsd > 0) pnl / t.riskUsd else 0.0)}R) | ${t.setup}", fontSize = 12.sp)
                TextButton(onClick = { scope.launch(Dispatchers.IO) { Core.manualClose(t.id) } }) { Text("بستن") }
            }
        }
        Text("گزارش لحظه‌ای", style = MaterialTheme.typography.titleMedium)
        for (l in Core.logs.take(40)) Text(l, fontSize = 11.sp)
    }
}

@Composable
fun ChartScreen() {
    val scope = rememberCoroutineScope()
    var sym by remember { mutableStateOf("BTCUSDT") }; var tf by remember { mutableStateOf(Cfg.s("scan.tf")) }
    var bars by remember { mutableFloatStateOf(120f) }; var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var info by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Page {
        OutlinedTextField(sym, { sym = it.uppercase() }, label = { Text("نماد") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (x in listOf("5m", "15m", "1h", "4h", "1d")) FilterChip(selected = tf == x, onClick = { tf = x }, label = { Text(x) })
        }
        Text("تعداد کندل: ${bars.toInt()}", fontSize = 12.sp)
        Slider(bars, { bars = it }, valueRange = 40f..300f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch(Dispatchers.IO) {
                    val cs = Data.klines(sym, tf, 500)
                    if (cs.size < 100) { info = "داده دریافت نشد"; busy = false; return@launch }
                    val a = Analyzer.analyze(cs)
                    val sig = SignalEngine.scan(sym, tf, a, emptyList(), Core.model, ArrayList()).maxByOrNull { it.quality }
                    val b = ChartRender.render(a, sig, bars.toInt(), title = "$sym $tf")
                    info = "رژیم ${a.regime} | روند ${a.trend} | ATR ${Core.fmt(a.a)} | RSI ${a.rsi[a.last].toInt()} | ADX ${a.adx.adx[a.last].toInt()} | سطوح ${a.levels.size} | خطوط ${a.lines.size} | ناحیه ${a.zones.size}" +
                        (sig?.let { "\nکاندید: ${it.side} ${it.setup} Q=${"%.1f".format(it.quality)} P=${(it.prob * 100).toInt()}%" } ?: "\nکاندیدی نیست")
                    withContext(Dispatchers.Main) { bmp = b; busy = false }
                }
            }) { Text(if (busy) "…" else "رسم و تحلیل") }
        }
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth()) }
        Text(info, fontSize = 12.sp)
        Text("لایه‌های نمایش را در تنظیمات (بخش «نمایش چارت») روشن/خاموش کنید.", fontSize = 11.sp)
    }
}

@Composable
fun ManualScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }; var verdict by remember { mutableStateOf<Verdict?>(null) }
    var res by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    fun run() {
        busy = true; res = ""
        scope.launch(Dispatchers.IO) {
            val v = try { Manual.validate(Manual.parse(text)) } catch (e: Exception) { Verdict(listOf(Check("خطا", "fail", e.message ?: "")), null) }
            var msg = ""
            if (v.sig != null && Cfg.b("man.autoExec")) msg = Core.executeManual(v.sig)
            withContext(Dispatchers.Main) { verdict = v; res = msg; busy = false }
        }
    }
    Page {
        Text("ثبت دستی سیگنال تلگرام", style = MaterialTheme.typography.titleMedium)
        Text("متن سیگنال را جای‌گذاری کنید؛ ایجنت اول اعتبارسنجی می‌کند، بعد با تأیید شما اجرا می‌شود.", fontSize = 12.sp)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("متن سیگنال") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: text
            }) { Text("📋 چسباندن") }
            Button(enabled = !busy && text.isNotBlank(), onClick = { run() }) { Text(if (busy) "…" else "بررسی") }
        }
        verdict?.let { v ->
            for (c in v.checks) Text((when (c.level) { "ok" -> "✅ "; "warn" -> "⚠️ "; else -> "❌ " }) + c.name + ": " + c.msg, fontSize = 13.sp)
            if (v.sig != null) {
                Text("✔️ سیگنال معتبر است — ${v.sig.side} ${v.sig.symbol}", color = Color(0xFF4CD964))
                Button(onClick = { scope.launch(Dispatchers.IO) { val m = Core.executeManual(v.sig); withContext(Dispatchers.Main) { res = m } } }) { Text("✅ تأیید و اجرا") }
            } else Text("⛔ سیگنال معتبر نیست و اجرا نمی‌شود.", color = Color(0xFFFF6B6B))
        }
        if (res.isNotEmpty()) Text(res)
    }
}

@Composable
fun TradesScreen() {
    val tk = Core.tick.collectAsState().value
    Page {
        Text("معاملات بسته (آخرین ۸۰) [$tk]", style = MaterialTheme.typography.titleMedium)
        for (t in Core.trades.filter { it.status == "CLOSED" }.sortedByDescending { it.closeTime }.take(80)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("${t.symbol} ${t.side} [${t.mode}/${t.kind}] ${t.setup}", fontSize = 13.sp)
                    Text("${Core.fmt(t.entry)} → ${Core.fmt(t.exitPrice)} | ${t.reason} | ${f2(t.realized)}$ (${f2(t.rMult)}R)", fontSize = 12.sp,
                        color = if (t.realized >= 0) Color(0xFF4CD964) else Color(0xFFFF6B6B))
                }
            }
        }
    }
}

@Composable
fun ReportsScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf("daily") }; var msg by remember { mutableStateOf("") }
    val tk = Core.tick.collectAsState().value
    val v = Reports.select(period); val s = Reports.stats(v)
    Page {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((k, l) in listOf("daily" to "روزانه", "weekly" to "هفتگی", "all" to "کلی")) FilterChip(selected = period == k, onClick = { period = k }, label = { Text(l) })
        }
        Text("[$tk] تعداد ${s.n} | برد ${f2(s.winRate * 100)}% | سود ${f2(s.net)}$ (${f2(s.netPct)}%)", fontSize = 13.sp)
        Text("میانگین R ${f2(s.avgR)} | PF ${f2(s.pf)} | دراوداون ${f2(s.maxDd)}$ (${f2(s.maxDdPct)}%) | کارمزد ${f2(s.fees)}$", fontSize = 13.sp)
        Text("بهترین ${f2(s.best)}$ | بدترین ${f2(s.worst)}$", fontSize = 13.sp)
        val eq = s.equity
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            if (eq.size >= 2) {
                val mn = eq.min(); val mx = eq.max(); val rng = (mx - mn).takeIf { it > 1e-9 } ?: 1.0
                for (i in 1 until eq.size) {
                    val x1 = size.width * (i - 1) / (eq.size - 1); val x2 = size.width * i / (eq.size - 1)
                    val y1 = size.height * (1 - ((eq[i - 1] - mn) / rng)).toFloat(); val y2 = size.height * (1 - ((eq[i] - mn) / rng)).toFloat()
                    drawLine(Color(0xFF4CD964), Offset(x1, y1), Offset(x2, y2), 4f)
                }
            }
        }
        Text("منحنی سود تجمعی", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    val p = Reports.saveDownload(ctx, "report_${period}_${Core.today()}.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Reports.xlsx(period))
                    msg = if (p != null) "ذخیره شد: $p" else "ذخیره ناموفق"
                }
            }) { Text("خروجی اکسل") }
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) { msg = if (Telegram.doc("report_$period.xlsx", Reports.xlsx(period), "گزارش $period")) "ارسال شد" else "ارسال ناموفق (تلگرام را بررسی کنید)" }
            }) { Text("ارسال به تلگرام") }
        }
        Text(msg, fontSize = 12.sp)
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }; var ver by remember { mutableIntStateOf(0) }
    Page {
        Text("تنظیمات (هر ماژول قابل خاموش/روشن و تنظیم است)", style = MaterialTheme.typography.titleMedium)
        Text(msg, fontSize = 12.sp, color = Color(0xFFFFD166))
        FlowRowButtons(
            listOf(
                "تست تلگرام" to { scope.launch(Dispatchers.IO) { msg = if (Telegram.text("✅ تست ایجنت")) "ارسال شد" else "ناموفق: توکن/ChatID/پروکسی/API را بررسی کنید" } },
                "تست بروکر" to { scope.launch(Dispatchers.IO) { msg = Broker.test() } },
                "بهینه‌سازی اکنون" to { scope.launch(Dispatchers.IO) { msg = "در حال اجرا…"; msg = try { Optimizer.run() } catch (e: Exception) { "خطا: ${e.message}" } } },
                "اعمال نتیجه بهینه‌سازی" to { if (Core.pendingOpt.isNotEmpty()) { Optimizer.apply(Core.pendingOpt); ver++; msg = "اعمال شد" } else { msg = "نتیجه‌ای در انتظار نیست" }; Unit },
                "آموزش مجدد از تاریخچه" to { scope.launch(Dispatchers.IO) { Core.model.bootstrapped = false; msg = "شروع شد (لاگ داشبورد)"; Core.bootstrapIfNeeded() } },
                "کپی تنظیمات" to {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("cfg", Cfg.exportJson())); msg = "تنظیمات (بدون کلیدها) کپی شد"
                },
                "بازگردانی از کلیپ‌بورد" to {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    msg = try { "${Cfg.importJson(cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "")} مورد بازگردانی شد" } catch (e: Exception) { "JSON نامعتبر" }; ver++
                },
                "بررسی نسخه جدید" to { scope.launch(Dispatchers.IO) { msg = checkUpdate() } },
                "ریست حساب و یادگیری" to { Store.reset(); msg = "ریست شد" }
            )
        )
        key(ver) {
            var last = ""
            for (p in Cfg.defs) {
                if (p.section != last) { Text(p.section, style = MaterialTheme.typography.titleSmall, color = Color(0xFF7FB3FF)); last = p.section }
                var v by remember(p.key, ver) { mutableStateOf(Cfg.s(p.key)) }
                if (p.kind == 'b') Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.label, Modifier.weight(1f), fontSize = 13.sp)
                    Switch(v == "1", { v = if (it) "1" else "0"; Cfg.set(p.key, v) })
                } else OutlinedTextField(v, { v = it; Cfg.set(p.key, it) }, label = { Text(p.label, fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun FlowRowButtons(items: List<Pair<String, () -> Any?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (l, a) -> OutlinedButton(onClick = { a() }, modifier = Modifier.weight(1f)) { Text(l, fontSize = 11.sp) } }
            }
        }
    }
}

private fun checkUpdate(): String {
    val repo = Cfg.s("upd.repo").trim()
    if (repo.isEmpty()) return "ریپوی گیت‌هاب در تنظیمات «به‌روزرسانی» وارد نشده"
    val s = Net.get("https://api.github.com/repos/$repo/releases/latest") ?: return "دریافت اطلاعات نسخه ناموفق"
    return try { val j = JSONObject(s); "آخرین نسخه: ${j.optString("tag_name")} — ${j.optString("html_url")}" } catch (e: Exception) { "پاسخ نامعتبر" }
}
