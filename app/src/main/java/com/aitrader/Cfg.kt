package com.aitrader

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

object Cfg {
    class P(val key: String, val label: String, val def: String, val kind: Char, val section: String)

    private lateinit var sp: SharedPreferences
    private lateinit var secure: SharedPreferences

    private fun b(k: String, l: String, d: Boolean, s: String) = P(k, l, if (d) "1" else "0", 'b', s)
    private fun n(k: String, l: String, d: String, s: String) = P(k, l, d, 'n', s)
    private fun t(k: String, l: String, d: String, s: String) = P(k, l, d, 's', s)
    private fun pw(k: String, l: String, s: String) = P(k, l, "", 'p', s)

    val defs: List<P> = listOf(
        // ---- حساب و ریسک
        n("acc.balance", "موجودی اولیه حساب مجازی ($)", "1000", "حساب و ریسک"),
        n("risk.pct", "ریسک هر معامله (٪ موجودی = 1R)", "1.0", "حساب و ریسک"),
        n("risk.maxLossR", "حداکثر ضرر هر معامله (R) — فاصله حد ضرر با این مقدار سایز می‌شود", "0.3", "حساب و ریسک"),
        n("risk.earlyR", "خروج زودهنگام در ضرر (R)", "0.2", "حساب و ریسک"),
        n("risk.maxLev", "سقف اهرم مؤثر (نسبت نوشنال به موجودی)", "5", "حساب و ریسک"),
        n("risk.dailyLoss", "توقف معاملات جدید با ضرر روزانه (٪)", "3", "حساب و ریسک"),
        n("risk.maxConsec", "توقف پس از N ضرر پشت‌سرهم", "4", "حساب و ریسک"),
        n("risk.pauseMin", "مدت توقف پس از ضررهای پشت‌سرهم (دقیقه)", "240", "حساب و ریسک"),
        n("risk.maxOpen", "حداکثر معاملات باز", "5", "حساب و ریسک"),
        n("risk.maxSameDir", "حداکثر معاملات هم‌جهت (همبستگی کریپتو)", "3", "حساب و ریسک"),
        n("risk.fee", "کارمزد هر سمت (٪)", "0.05", "حساب و ریسک"),
        n("risk.slip", "اسلیپیج (٪)", "0.03", "حساب و ریسک"),
        // ---- اسکن
        t("scan.symbols", "نمادها (با کاما)", "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT", "اسکن و داده"),
        t("scan.tf", "تایم‌فریم اجرا", "15m", "اسکن و داده"),
        t("scan.htf", "تایم‌فریم‌های بالاتر (تأیید)", "1h,4h", "اسکن و داده"),
        n("manage.sec", "فاصله مدیریت معاملات باز (ثانیه)", "20", "اسکن و داده"),
        t("data.sources", "منابع داده به ترتیب (binance,bybit,okx)", "binance,bybit,okx", "اسکن و داده"),
        t("net.proxy", "پروکسی HTTP (host:port) — برای تلگرام و صرافی‌ها", "", "اسکن و داده"),
        // ---- فیلتر
        n("gate.prob", "حداقل احتمال مدل (٪)", "65", "فیلتر سیگنال"),
        n("gate.stat", "حداقل کران آماری Wilson (٪)", "50", "فیلتر سیگنال"),
        n("gate.quality", "حداقل کیفیت (۰ تا ۱۰)", "6", "فیلتر سیگنال"),
        n("gate.minRR", "حداقل RR تا هدف اول", "1.5", "فیلتر سیگنال"),
        n("gate.warmup", "حداقل نمونه آماری برای معامله/تلگرام", "30", "فیلتر سیگنال"),
        b("shadow.on", "معاملات سایه (یادگیری از سیگنال‌های ردشده بدون اثر روی موجودی)", true, "فیلتر سیگنال"),
        // ---- ماژول‌های تحلیل
        b("m.sr", "حمایت/مقاومت ضعیف و قوی", true, "ماژول‌های تحلیل"),
        b("m.trendlines", "خطوط روند", true, "ماژول‌های تحلیل"),
        b("m.smc", "اسمارت مانی (OB, FVG, BOS, CHoCH, Sweep)", true, "ماژول‌های تحلیل"),
        b("m.volprofile", "پروفایل حجم (POC/VA)", true, "ماژول‌های تحلیل"),
        b("m.patterns", "الگوهای کندلی", true, "ماژول‌های تحلیل"),
        b("m.mtf", "تأیید چندتایم‌فریمی", true, "ماژول‌های تحلیل"),
        b("m.regime", "فیلتر رژیم بازار", true, "ماژول‌های تحلیل"),
        b("m.visual", "بررسی چشمی چارت (فضای هدف، کشیدگی، کندل غول‌پیکر، نویز)", true, "ماژول‌های تحلیل"),
        // ---- ستاپ‌ها
        b("s.pullback", "ستاپ پولبک روند", true, "ستاپ‌ها"),
        b("s.sweep", "ستاپ شکار نقدینگی", true, "ستاپ‌ها"),
        b("s.breakout", "ستاپ شکست و ریتست", true, "ستاپ‌ها"),
        b("s.range", "ستاپ رنج (کف/سقف)", true, "ستاپ‌ها"),
        b("s.obfvg", "ستاپ OB/FVG", true, "ستاپ‌ها"),
        // ---- مدیریت معامله
        b("t.pyramid", "معاملات هرمی", true, "مدیریت معامله"),
        b("t.partials", "خروج پله‌ای", true, "مدیریت معامله"),
        b("t.trail", "تریلینگ ATR", true, "مدیریت معامله"),
        b("t.corrExit", "خروج در اصلاح بزرگ‌تر از درصد تنظیمی", true, "مدیریت معامله"),
        b("t.reentry", "ورود مجدد پس از پایان اصلاح", true, "مدیریت معامله"),
        b("t.earlyExit", "خروج زودهنگام قبل از حد ضرر", true, "مدیریت معامله"),
        b("t.counter", "معامله خلاف جهت در ادامه حرکت مخالف (ریسک کم)", true, "مدیریت معامله"),
        b("t.deepCorr", "معامله در جهت اصلاح متوسط/عمیق (با احتیاط)", true, "مدیریت معامله"),
        n("t.corrPct", "درصد اصلاح برای خروج (٪ از سود ماکزیمم)", "20", "مدیریت معامله"),
        n("t.pyrMax", "حداکثر تعداد پله هرمی", "3", "مدیریت معامله"),
        n("t.pyrStepU", "فاصله هر پله هرمی (واحد U = فاصله حد ضرر)", "1.0", "مدیریت معامله"),
        n("t.pyrRiskK", "ضریب ریسک هر پله هرمی", "0.5", "مدیریت معامله"),
        n("t.p1AtU", "خروج پله اول در (U)", "1.0", "مدیریت معامله"),
        n("t.p1Pct", "درصد خروج پله اول", "30", "مدیریت معامله"),
        n("t.p2AtU", "خروج پله دوم در (U)", "2.0", "مدیریت معامله"),
        n("t.p2Pct", "درصد خروج پله دوم", "30", "مدیریت معامله"),
        n("t.trailAtr", "ضریب ATR تریلینگ", "2.0", "مدیریت معامله"),
        n("t.counterRiskK", "ضریب ریسک معامله خلاف جهت", "0.5", "مدیریت معامله"),
        n("t.reentryProb", "حداقل احتمال ادامه برای ورود مجدد (٪)", "70", "مدیریت معامله"),
        n("t.earlyProbMin", "خروج زودهنگام اگر احتمال ادامه کمتر از (٪)", "50", "مدیریت معامله"),
        // ---- یادگیری و بهینه‌سازی
        b("l.enabled", "یادگیری آنلاین (لجستیک + آمار ستاپ)", true, "یادگیری و بهینه‌سازی"),
        n("l.lr", "نرخ یادگیری", "0.05", "یادگیری و بهینه‌سازی"),
        b("opt.on", "بهینه‌سازی روزانه پارامترها (Walk-forward ساده)", true, "یادگیری و بهینه‌سازی"),
        n("opt.minGain", "توقف وقتی بهبود کمتر از (٪)", "0.01", "یادگیری و بهینه‌سازی"),
        b("opt.auto", "اعمال خودکار نتیجه بهینه‌سازی", false, "یادگیری و بهینه‌سازی"),
        n("opt.hourUtc", "ساعت اجرای بهینه‌سازی (UTC)", "23", "یادگیری و بهینه‌سازی"),
        // ---- تلگرام
        b("tg.on", "ارسال به تلگرام", false, "تلگرام"),
        pw("tg.token", "توکن ربات", "تلگرام"),
        t("tg.chat", "Chat ID", "", "تلگرام"),
        t("tg.api", "آدرس API تلگرام (برای پروکسی/آینه)", "https://api.telegram.org", "تلگرام"),
        b("tg.chart", "ارسال تصویر چارت با سیگنال", true, "تلگرام"),
        b("tg.heartbeat", "پیام سلامت هر ۶ ساعت", true, "تلگرام"),
        b("tg.shadow", "ارسال سیگنال‌های سایه/ردشده", false, "تلگرام"),
        b("rep.auto", "ارسال اکسل روزانه به تلگرام", true, "تلگرام"),
        // ---- بروکر
        b("mode.broker", "اجرای هم‌زمان روی بروکر (Binance Futures)", false, "بروکر"),
        b("bk.live", "حساب واقعی (خاموش = دمو/Testnet)", false, "بروکر"),
        b("bk.arm", "تأیید نهایی معامله واقعی", false, "بروکر"),
        pw("bk.key", "API Key", "بروکر"),
        pw("bk.secret", "API Secret", "بروکر"),
        // ---- ثبت دستی
        b("man.autoExec", "اجرای خودکار سیگنال دستی در صورت معتبر بودن", false, "ثبت دستی"),
        n("man.minRR", "حداقل RR سیگنال دستی", "1.2", "ثبت دستی"),
        n("man.maxDevPct", "حداکثر فاصله ورود از قیمت (٪)", "1.5", "ثبت دستی"),
        b("man.needAgree", "رد سیگنالی که ایجنت با آن مخالف است", true, "ثبت دستی"),
        // ---- نمایش چارت
        b("draw.levels", "نمایش سطوح", true, "نمایش چارت"),
        b("draw.lines", "نمایش خطوط روند", true, "نمایش چارت"),
        b("draw.zones", "نمایش OB/FVG", true, "نمایش چارت"),
        b("draw.ema", "نمایش EMA", true, "نمایش چارت"),
        b("draw.profile", "نمایش POC/VA", true, "نمایش چارت"),
        // ---- به‌روزرسانی
        t("upd.repo", "ریپوی گیت‌هاب (owner/repo) برای بررسی نسخه جدید", "", "به‌روزرسانی")
    )
    private val byKey = defs.associateBy { it.key }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        secure = try {
            val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, "sec", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            ctx.getSharedPreferences("sec_fallback", Context.MODE_PRIVATE)
        }
    }

    fun s(k: String): String {
        val p = byKey[k]
        val store = if (p?.kind == 'p') secure else sp
        return store.getString(k, p?.def ?: "") ?: ""
    }
    fun set(k: String, v: String) {
        val p = byKey[k]
        (if (p?.kind == 'p') secure else sp).edit().putString(k, v).apply()
    }
    fun b(k: String): Boolean = s(k) == "1"
    fun d(k: String): Double = s(k).replace(',', '.').toDoubleOrNull() ?: (byKey[k]?.def?.toDoubleOrNull() ?: 0.0)

    fun exportJson(): String {
        val j = JSONObject()
        for (p in defs) if (p.kind != 'p') j.put(p.key, s(p.key))
        return j.toString(2)
    }
    fun importJson(txt: String): Int {
        val j = JSONObject(txt); var c = 0
        for (p in defs) if (p.kind != 'p' && j.has(p.key)) { set(p.key, j.getString(p.key)); c++ }
        return c
    }
}
