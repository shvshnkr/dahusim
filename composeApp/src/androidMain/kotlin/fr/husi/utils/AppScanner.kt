package fr.husi.utils

import android.content.pm.PackageManager
import android.os.Build
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import fr.husi.ktx.Logs
import java.io.File
import java.util.zip.ZipFile

object AppScanner {
    private val skipPrefixList by lazy {
        listOf(
            "com.google",
            "com.android.chrome",
            "com.android.vending",
            "com.microsoft",
            "com.apple",
            "com.zhiliaoapp.musically", // Banned by China
            "com.android.providers.downloads", // Download manager, which may has Chinese SDK.
        )
    }

    private val chinaAppPrefixList by lazy {
        listOf(
            "com.tencent",
            "com.alibaba",
            "com.umeng",
            "com.qihoo",
            "com.ali",
            "com.alipay",
            "com.amap",
            "com.sina",
            "com.weibo",
            "com.vivo",
            "com.xiaomi",
            "com.huawei",
            "com.taobao",
            "com.secneo",
            "s.h.e.l.l",
            "com.stub",
            "com.kiwisec",
            "com.secshell",
            "com.wrapper",
            "cn.securitystack",
            "com.mogosec",
            "com.secoen",
            "com.netease",
            "com.mx",
            "com.qq.e",
            "com.baidu",
            "com.bytedance",
            "com.bugly",
            "com.miui",
            "com.oppo",
            "com.coloros",
            "com.iqoo",
            "com.meizu",
            "com.gionee",
            "cn.nubia",
            "com.oplus",
            "andes.oplus",
            "com.unionpay",
            "cn.wps",
        )
    }

    private val chinaAppRegex by lazy {
        ("(" + chinaAppPrefixList.joinToString("|").replace(".", "\\.") + ").*").toRegex()
    }

    // Known package prefixes of popular apps from the Russian ecosystem: banks, state
    // services, marketplaces, messengers and major local platforms. Used by the
    // "scan Russian apps" action to select them for per-app bypass routing.
    private val russianAppPrefixList by lazy {
        listOf(
            // Banks
            "ru.sberbankmobile",
            "ru.sberbank",
            "ru.sberbankmobile_android_pay",
            "ru.alfabank",
            "ru.vtb24",
            "ru.vtb",
            "ru.rosbank",
            "ru.raiffeisennews",
            "ru.raiffeisen",
            "ru.gazprombank",
            "ru.rshb",
            "ru.mkb",
            "ru.psbank",
            "com.idamob.tinkoff",
            "com.ftband.mono",
            "ru.tinkoff",
            "ru.akbars",
            "ru.otpbank",
            "ru.otkritie",
            "ru.yoomoney",
            // Government & utilities
            "ru.rostel",
            "ru.mos",
            "ru.rt.mlk",
            "ru.rzd",
            "ru.nalog",
            "ru.fssp",
            "ru.gosuslugi",
            "ru.rtlabs",
            "ru.pochta",
            "ru.csc",
            // Marketplaces
            "ru.ozon",
            "com.wildberries",
            "ru.wildberries",
            "ru.beru",
            "ru.aliexpress",
            "com.avito",
            "ru.avito",
            "ru.yandex.market",
            "com.lamoda",
            "ru.dns_shop",
            "ru.mvideo",
            "ru.eldorado",
            "com.citilink",
            // Yandex
            "ru.yandex",
            "com.yandex",
            // VK / mail.ru / OK
            "com.vkontakte",
            "ru.mail",
            "com.mail",
            "ru.ok",
            "ru.mamba",
            // Messengers / media popular in RU
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "ua.itaysonlab.messenger",
            "com.vk.im",
            "com.icq",
            "ru.rutube",
            // Telecom operators
            "ru.megafon",
            "ru.mts",
            "ru.beeline",
            "ru.tele2",
            "ru.feature",
            // Taxi / delivery
            "ru.yandex.taxi",
            "com.citymobil",
            "ru.citymobil",
            "com.cmtelematics",
            "ru.foodfox",
            "ru.sbermarket",
            "ru.yandex.eda",
            "ru.delivery",
            "ru.sravni",
            // Kinopoisk / media
            "ru.kinopoisk",
            "ru.ivi.client",
            "ru.okko",
            "ru.mts.mtstv",
            "ru.more.play",
            "ru.rt.video",
            // Music
            "ru.yandex.music",
            "com.zvooq",
            "ru.zvuk",
            // Antivirus / local AV
            "com.kaspersky",
            "ru.drweb",
        )
    }

    private val russianAppRegex by lazy {
        ("(" + russianAppPrefixList.joinToString("|").replace(".", "\\.") + ").*").toRegex()
    }

    private fun matchesSdkIndicators(
        packageName: String,
        packageManager: PackageManager,
        nameRegex: Regex,
    ): Boolean {
        val packageManagerFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        }
        if (packageName.matches(nameRegex)) {
            Logs.d("Match package name: $packageName")
            return true
        }
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(packageManagerFlags.toLong()),
                )
            } else {
                packageManager.getPackageInfo(packageName, packageManagerFlags)
            }
            packageInfo.services?.forEach {
                if (it.name.matches(nameRegex)) {
                    Logs.d("Match service ${it.name} in $packageName")
                    return true
                }
            }
            packageInfo.activities?.forEach {
                if (it.name.matches(nameRegex)) {
                    Logs.d("Match activity ${it.name} in $packageName")
                    return true
                }
            }
            packageInfo.receivers?.forEach {
                if (it.name.matches(nameRegex)) {
                    Logs.d("Match receiver ${it.name} in $packageName")
                    return true
                }
            }
            packageInfo.providers?.forEach {
                if (it.name.matches(nameRegex)) {
                    Logs.d("Match provider ${it.name} in $packageName")
                    return true
                }
            }
            ZipFile(File(packageInfo.applicationInfo!!.publicSourceDir)).use {
                for (packageEntry in it.entries()) {
                    if (packageEntry.name.startsWith("firebase-")) return false
                }
                for (packageEntry in it.entries()) {
                    if (!(packageEntry.name.startsWith("classes") && packageEntry.name.endsWith(
                            ".dex",
                        ))
                    ) {
                        continue
                    }
                    if (packageEntry.size > 15000000) {
                        Logs.d("Confirm $packageName due to large dex file")
                        return true
                    }
                    val input = it.getInputStream(packageEntry).buffered()
                    val dexFile = try {
                        DexBackedDexFile.fromInputStream(null, input)
                    } catch (e: Exception) {
                        Logs.e("Error reading dex file", e)
                        return false
                    }
                    for (clazz in dexFile.classes) {
                        val clazzName =
                            clazz.type.substring(1, clazz.type.length - 1).replace("/", ".")
                                .replace("$", ".")
                        if (clazzName.matches(nameRegex)) {
                            Logs.d("Match $clazzName in $packageName")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logs.e("Error scanning package $packageName", e)
        }
        return false
    }

    fun isRussianApp(packageName: String, packageManager: PackageManager): Boolean {
        skipPrefixList.forEach {
            if (packageName == it || packageName.startsWith("$it.")) return false
        }
        return matchesSdkIndicators(packageName, packageManager, russianAppRegex)
    }

    fun isChinaApp(packageName: String, packageManager: PackageManager): Boolean {
        skipPrefixList.forEach {
            if (packageName == it || packageName.startsWith("$it.")) return false
        }
        return matchesSdkIndicators(packageName, packageManager, chinaAppRegex)
    }

}