package com.androidharness.app.androiduse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

internal enum class MapHandoffRoute(val ownerPackage: String?) {
    BAIDU("com.baidu.BaiduMap"), AMAP("com.autonavi.minimap"), GEO(null);
}

internal fun mapIntent(route: MapHandoffRoute, query: String): Intent = when (route) {
    MapHandoffRoute.GEO -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
    // POI OneSearch (no coordinates or made-up region). https://lbs.baidu.com/docs/webapi?title=mapadjustment/uri/andriod
    MapHandoffRoute.BAIDU -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("baidumap").authority("map")
        .path("/place/search").appendQueryParameter("query", query)
        .appendQueryParameter("src", "andr.androidharness.app").build())
    // Search results, not turn-by-turn navigation. https://lbs.amap.com/api/amap-mobile/guide/android/search
    MapHandoffRoute.AMAP -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("androidamap").authority("poi")
        .appendQueryParameter("sourceApplication", "AndroidHarness").appendQueryParameter("keywords", query).build())
}

internal class MapSearchHandler(val route: MapHandoffRoute, val info: ResolveInfo) {
    val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    fun explicitIntent(query: String) =
        mapIntent(route, query).setPackage(component.packageName).setComponent(component)
}

/** A package receives at most one choice: its own POI endpoint first, the generic map contract otherwise. */
@Suppress("DEPRECATION")
internal fun mapSearchHandlers(context: Context, query: String, targetPackage: String? = null): List<MapSearchHandler> {
    val pm = context.packageManager
    val flags = PackageManager.MATCH_DEFAULT_ONLY
    // Don't pass a place to generic web front ends that happen to register themselves for geo URIs.
    val browsers = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))
        .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(targetPackage), flags or PackageManager.GET_RESOLVED_FILTER)
        .filter { entry -> entry.filter?.let { filter ->
            filter.countDataAuthorities() == 0 || (0 until filter.countDataAuthorities()).any { filter.getDataAuthority(it).host == "*" }
        } == true }.mapTo(hashSetOf()) { it.activityInfo.packageName }
    val found = linkedMapOf<String, MapSearchHandler>()
    for (route in MapHandoffRoute.entries) {
        val vendor = route.ownerPackage
        if (vendor != null && targetPackage != null && targetPackage != vendor) continue
        val scope = vendor ?: targetPackage
        if (scope != null && (scope in found || scope in browsers)) continue
        val intent = mapIntent(route, query).setPackage(scope)
        for (info in pm.queryIntentActivities(intent, flags or PackageManager.GET_RESOLVED_FILTER)) {
            val activity = info.activityInfo ?: continue
            val pkg = activity.packageName
            if ((scope != null && pkg != scope) || !activity.exported || pkg in browsers || pkg in found) continue
            if (activity.permission != null && context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED) continue
            if (route == MapHandoffRoute.GEO && info.filter?.hasDataScheme("geo") != true) continue
            found[pkg] = MapSearchHandler(route, info)
        }
    }
    return found.values.toList()
}
