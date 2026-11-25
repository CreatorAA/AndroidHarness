package com.androidharness.app.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.androidharness.app.androiduse.MapHandoffRoute
import com.androidharness.app.androiduse.mapIntent
import com.androidharness.app.androiduse.mapSearchHandlers
import java.text.Normalizer
import java.util.Locale

internal data class AppHandoffRequest(val action: String, val requestedApp: String, val query: String? = null) {
    fun validate() {
        val search = action == "map_search"
        require(search || action == "open_app") { "Unsupported app handoff." }
        require(requestedApp.length <= 200 && (search || requestedApp.isNotBlank())) { "Specify the app name." }
        require(if (search) !query.isNullOrBlank() && query.length <= 500 else query == null) {
            "Map search needs a place name of 1–500 characters; opening an app takes no search terms."
        }
    }
}

internal enum class AppHandoffStatus { READY, APP_NOT_FOUND, AMBIGUOUS, OPEN_ONLY, NO_HANDLER }

internal data class AppHandoffChoice(
    val label: String,
    val packageName: String,
    val action: String,
    internal val activity: String,
    internal val mapRoute: MapHandoffRoute? = null,
)

internal data class AppHandoffPlan(
    val request: AppHandoffRequest,
    val status: AppHandoffStatus,
    val choices: List<AppHandoffChoice>,
) {
    val requestedApp get() = request.requestedApp
    val query get() = request.query
}

/** Local presentation inventory. Only choices for this particular request leave the resolver. */
internal data class HandoffApp(
    val label: String, val pkg: String, val launcher: String? = null,
    val mapActivity: String? = null, val mapRoute: MapHandoffRoute? = null,
    val otherLabels: Set<String> = emptySet(),
) {
    fun open() = launcher?.let { AppHandoffChoice(label, pkg, "open_app", it) }
    fun search() = mapActivity?.let { AppHandoffChoice(label, pkg, "map_search", it, mapRoute) }
}

internal object AppHandoffPolicy {
    const val BAIDU = "com.baidu.BaiduMap"
    const val AMAP = "com.autonavi.minimap"
    private fun key(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .filterNot(Char::isWhitespace).lowercase(Locale.ROOT)
    private val brands = mapOf(
        BAIDU to listOf("百度地图", "百度地圖", "Baidu Maps", "Baidu Map"),
        AMAP to listOf("高德", "高德地图", "高德地圖", "AMap", "Gaode Map", "Gaode Maps"),
        "com.tencent.map" to listOf("腾讯地图", "騰訊地圖", "Tencent Maps"),
        "com.google.android.apps.maps" to listOf("谷歌地图", "谷歌地圖", "Google 地图", "Google Maps"),
    )
    fun requestedPackage(request: AppHandoffRequest): String? {
        val name = key(request.requestedApp)
        // “打开百度” means the Baidu app, not necessarily its map. Only expand the bare brand for a map task.
        if (request.action == "map_search" && name in setOf("百度", "baidu")) return BAIDU
        return brands.entries.firstOrNull { (pkg, aliases) ->
            name == key(pkg) || aliases.any { name == key(it) }
        }?.key
    }

    fun plan(request: AppHandoffRequest, apps: List<HandoffApp>): AppHandoffPlan {
        request.validate()
        val search = request.action == "map_search"
        val name = key(request.requestedApp)
        val maps = apps.mapNotNull { it.search() }.sortedBy { it.label }
        fun result(status: AppHandoffStatus, choices: List<AppHandoffChoice> = emptyList()) =
            AppHandoffPlan(request, status, choices)
        if (search && name in setOf("", "地图", "地圖", "map", "maps")) {
            return result(when (maps.size) { 0 -> AppHandoffStatus.NO_HANDLER; 1 -> AppHandoffStatus.READY; else -> AppHandoffStatus.AMBIGUOUS }, maps)
        }
        val known = requestedPackage(request)
        val matches = if (known != null) apps.filter { it.pkg == known } else apps.filter {
            it.pkg.equals(request.requestedApp.trim(), true) || key(it.label) == name || it.otherLabels.any { alias -> key(alias) == name }
        }
        if (matches.isEmpty()) {
            // A differently packaged app with the same label is not the requested brand.
            val alternatives = if (search) maps.filterNot { known != null && key(it.label) == name } else emptyList()
            return result(AppHandoffStatus.APP_NOT_FOUND, alternatives)
        }
        if (matches.size > 1) {
            val choices = matches.mapNotNull { if (search) it.search() ?: it.open() else it.open() }
            return result(if (choices.isEmpty()) AppHandoffStatus.NO_HANDLER else AppHandoffStatus.AMBIGUOUS, choices)
        }
        val app = matches.single()
        val exact = if (search) app.search() else app.open()
        if (exact != null) return result(AppHandoffStatus.READY, listOf(exact))
        val open = app.open()
        if (search && open != null) return result(AppHandoffStatus.OPEN_ONLY, listOf(open) + maps)
        return result(AppHandoffStatus.NO_HANDLER, if (search) maps else emptyList())
    }
}

/** No web links, arbitrary components, navigation or system settings intents are accepted as task arguments. */
internal class AppHandoffResolver(private val context: Context) {
    private val pm get() = context.packageManager

    fun resolve(action: String, requestedApp: String, query: String? = null) =
        resolve(AppHandoffRequest(action, requestedApp, query))

    @Suppress("DEPRECATION")
    fun resolve(request: AppHandoffRequest): AppHandoffPlan {
        request.validate()
        val knownPackage = AppHandoffPolicy.requestedPackage(request)
        val apps = linkedMapOf<String, HandoffApp>()
        fun remember(info: ResolveInfo): HandoffApp {
            val a = info.activityInfo
            return apps.getOrPut(a.packageName) {
                HandoffApp(a.applicationInfo.loadLabel(pm).toString().ifBlank { a.packageName }, a.packageName)
            }
        }
        // A brand/package we already know does not require loading every launcher's labels.
        for (info in handlers(launcher().setPackage(knownPackage))) {
            val app = remember(info)
            apps[app.pkg] = app.copy(launcher = app.launcher ?: info.activityInfo.name,
                otherLabels = app.otherLabels + info.loadLabel(pm).toString())
        }
        if (request.action == "map_search" && request.query != null) {
            for (handler in mapSearchHandlers(context, request.query)) {
                val app = remember(handler.info)
                apps[app.pkg] = app.copy(mapActivity = handler.component.className, mapRoute = handler.route)
            }
        }
        // Distinguish a known, installed app with no usable entry point from an absent app.
        val pkg = knownPackage
        if (pkg != null && pkg !in apps) try {
            val info = pm.getApplicationInfo(pkg, 0)
            apps[pkg] = HandoffApp(info.loadLabel(pm).toString(), pkg)
        } catch (_: PackageManager.NameNotFoundException) { }
        return AppHandoffPolicy.plan(request, apps.values.toList())
    }

    /** Dispatch the exact resolved entry authorized by the user's command or chosen in the panel. */
    fun dispatch(plan: AppHandoffPlan, choice: AppHandoffChoice) {
        require(plan.choices.any { it === choice }) { "This is not a choice from the current request." }
        val isMap = choice.action == "map_search"
        val intent = if (isMap) {
            check(plan.request.action == "map_search")
            mapIntent(checkNotNull(choice.mapRoute), checkNotNull(plan.query))
        } else {
            check(choice.action == "open_app")
            launcher()
        }.setPackage(choice.packageName)
        val current = handlers(intent, if (isMap) PackageManager.MATCH_DEFAULT_ONLY else 0)
            .any { it.activityInfo.packageName == choice.packageName && it.activityInfo.name == choice.activity }
        check(current) { "The app or its action is no longer available." }
        context.startActivity(intent.setComponent(ComponentName(choice.packageName, choice.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Suppress("DEPRECATION")
    private fun handlers(intent: Intent, flags: Int = 0) = pm.queryIntentActivities(intent, flags).filter {
        val a = it.activityInfo
        a != null && a.exported && (a.permission == null ||
            context.checkSelfPermission(a.permission) == PackageManager.PERMISSION_GRANTED)
    }

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}
