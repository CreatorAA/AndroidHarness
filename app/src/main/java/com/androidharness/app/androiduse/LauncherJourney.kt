package com.androidharness.app.androiduse

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.util.Locale
import kotlinx.coroutines.delay

/** A single approved trip via this profile's actual launcher, never an application launch intent. */
internal data class LauncherTrip(val home: String, val destination: String, val labels: List<String>) {
    companion object {
        @Suppress("DEPRECATION")
        fun resolve(context: Context, pkg: String): LauncherTrip {
            PhoneScope.requireApp(pkg, context.packageName)
            val pm = context.packageManager
            val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            require(!home.isNullOrBlank() && home != "android" && home != pkg) { "Select a default home app and a different destination." }
            PhoneScope.requireApp(home, context.packageName)
            val ours = launchLabels(context, pkg)
            require(ours.isNotEmpty()) { "No uniquely labelled launcher icon is available for that package." }
            return LauncherTrip(home, pkg, ours)
        }
        /** A direct package switch does not require a usable default launcher or a unique icon. */
        @Suppress("DEPRECATION")
        fun launchLabels(context: Context, pkg: String): List<String> {
            val pm = context.packageManager
            val entries = launchableActivities(context)
            val labels = entries.flatMap { r -> listOf(r.loadLabel(pm), r.activityInfo.applicationInfo.loadLabel(pm))
                .map { it.toString().trim() to r.activityInfo.packageName } }.distinct()
            return labels.filter { it.second == pkg && it.first.isNotEmpty() && it.first.length <= 128 && it.first.none(Char::isISOControl) }
                .map { it.first }.filter { label -> labels.none { it.first.equals(label, true) && it.second != pkg } }
        }
    }
}

/** Normal package resolution respects runtime-enabled aliases, including those disabled in the manifest. */
@Suppress("DEPRECATION")
internal fun launchableActivities(context: Context): List<ResolveInfo> =
    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo?.exported == true && (it.activityInfo.permission == null ||
            context.checkSelfPermission(it.activityInfo.permission) == PackageManager.PERMISSION_GRANTED) }

/** Launcher frames never leave this local traversal. Only the confirmed destination can take over the task. */
internal class LauncherJourney(
    private val trip: LauncherTrip,
    private val driver: PhoneDriver,
    private val checkGrant: suspend () -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val methods = linkedSetOf<String>()
    private var moves = 0

    suspend fun run(source: PhoneFrame): List<String> {
        val home = trip.home
        suspend fun read(allowed: Set<String>): PhoneFrame {
            checkGrant()
            return driver.observeInApps(allowed).also { checkGrant() }
        }
        suspend fun step(frame: PhoneFrame, action: PhoneAction, method: String) {
            check(++moves <= 32) { "The launcher search reached its step limit; no further input was sent." }
            checkGrant()
            driver.dispatch(frame, action)
            methods += method
            pause(450)
        }
        suspend fun awaitApp(from: String, to: String): PhoneFrame {
            repeat(10) {
                val frame = read(setOf(from, to))
                if (frame.packageName == to) return frame
                pause(140)
            }
            error("The requested app did not appear; the click or Home action was not repeated.")
        }
        suspend fun swipe(frame: PhoneFrame, direction: String) {
            val w = frame.width; val h = frame.height
            val (x, y, toX, toY) = when (direction) {
                "up" -> listOf(w / 2, h * 3 / 4, w / 2, h / 4)
                "left" -> listOf(w * 4 / 5, h / 2, w / 5, h / 2)
                else -> listOf(w / 5, h / 2, w * 4 / 5, h / 2)
            }
            step(frame, PhoneAction("swipe", x = x, y = y, toX = toX, toY = toY), "swipe_$direction")
        }
        suspend fun icon(frame: PhoneFrame): Boolean {
            val hit = launcherIcon(frame, trip.labels) ?: return false
            step(frame, PhoneAction("tap", node = hit.id), "tap_icon")
            awaitApp(home, trip.destination)
            return true
        }
        val triedFolders = mutableSetOf<Pair<String, String>>()
        suspend fun inspectPage(initial: PhoneFrame): Boolean {
            var frame = initial
            if (icon(frame)) return true
            while (triedFolders.size < 8) {
                val folder = frame.nodes.firstOrNull { n -> n.isLauncherFolder() &&
                    (n.text to n.description) !in triedFolders } ?: break
                triedFolders += folder.text to folder.description
                step(frame, PhoneAction("tap", node = folder.id), "open_folder")
                val inside = read(setOf(home))
                if (icon(inside)) return true
                if (inside.nodes == frame.nodes) break
                step(inside, PhoneAction("back"), "leave_folder")
                frame = read(setOf(home))
            }
            return false
        }
        var frame = source
        if (frame.packageName != home) {
            step(frame, PhoneAction("home"), "home")
            frame = awaitApp(source.packageName, home)
        } else frame = read(setOf(home))
        if (inspectPage(frame)) return methods.toList()
        // A launcher may rebind its accessibility tree after folders close. Take a fresh driver frame.
        frame = read(setOf(home))
        val drawerFirst = frame.nodes.any { it.isDrawerEntry() }
        suspend fun drawer(): Boolean {
            val before = read(setOf(home))
            val entry = before.nodes.singleOrNull { it.clickable && it.isDrawerEntry() }
            if (entry != null) step(before, PhoneAction("tap", node = entry.id), "open_drawer")
            else swipe(before, "up")
            var list = read(setOf(home))
            if (icon(list)) return true
            val field = list.nodes.firstOrNull { it.editable && isAppSearch(it) }
            if (field != null) {
                step(list, PhoneAction("set_text", node = field.id, text = trip.labels.first()), "search_apps")
                repeat(4) {
                    list = read(setOf(home))
                    if (icon(list)) return true
                    pause(160)
                }
                return false
            }
            val seen = mutableSetOf<List<Triple<String, String, Int>>>()
            repeat(5) {
                if (!seen.add(list.pageKey())) return false
                swipe(list, "up")
                list = read(setOf(home))
                if (icon(list)) return true
            }
            return false
        }
        if (drawerFirst) {
            if (drawer()) return methods.toList()
            step(read(setOf(home)), PhoneAction("home"), "return_home")
        }
        for (direction in listOf("left", "right")) {
            if (direction == "right") step(read(setOf(home)), PhoneAction("home"), "return_home")
            val seen = mutableSetOf<List<Triple<String, String, Int>>>()
            frame = read(setOf(home))
            seen += frame.pageKey()
            for (page in 1..8) {
                swipe(frame, direction)
                frame = read(setOf(home))
                if (!seen.add(frame.pageKey())) break
                if (inspectPage(frame)) return methods.toList()
                frame = read(setOf(home))
            }
        }
        if (!drawerFirst) {
            step(read(setOf(home)), PhoneAction("home"), "return_home")
            if (drawer()) return methods.toList()
        }
        error("This launcher did not expose a unique matching icon. The app was not opened. Ask the user whether to open it directly instead.")
    }
}

private fun PhoneFrame.pageKey() = nodes.asSequence().filter { it.clickable }
    .map { Triple(it.text, it.description, it.left / 10 + 1000 * (it.top / 10)) }.toList()

private fun PhoneNode.isDrawerEntry(): Boolean {
    val id = resource.substringAfterLast('/').lowercase(Locale.ROOT)
    return id in setOf("all_apps_button", "apps_drawer_button", "all_apps_handle", "apps_drawer_handle") ||
        listOf(text.trim(), description.trim()).any { it.equals("All apps", true) || it == "所有应用" || it == "全部应用" }
}

private fun PhoneNode.isLauncherFolder(): Boolean {
    val desc = description.lowercase(Locale.ROOT)
    val type = viewClass.substringAfterLast('.')
    val id = resource.substringAfterLast('/').lowercase(Locale.ROOT)
    return clickable && !editable && (type.endsWith("FolderIcon") || type == "FolderIconView" ||
        id in setOf("folder_icon", "foldericon", "folder_icon_view") ||
        desc.startsWith("folder:") || desc.startsWith("folder ") || desc.startsWith("文件夹") ||
        desc.endsWith("文件夹") || desc.contains("，文件夹"))
}

private fun isAppSearch(node: PhoneNode): Boolean {
    val id = node.resource.substringAfterLast('/').lowercase(Locale.ROOT)
    return id in setOf("apps_search", "apps_search_box", "search_box_input", "all_apps_search_box", "search_src_text") ||
        listOf(node.text, node.description).any { it.contains("search apps", true) || it.contains("搜索应用") }
}

internal fun launcherIcon(frame: PhoneFrame, labels: List<String>): PhoneNode? {
    val hits = frame.nodes.filter { n -> !n.editable && !n.isLauncherFolder() && labels.any { label ->
        label.equals(n.text.trim(), true) || label.equals(n.description.trim(), true)
    } }
    return hits.mapNotNull { n ->
        if (n.clickable) n else frame.nodes.filter { parent -> parent.clickable && !parent.editable &&
            n.id.startsWith(parent.id + ".") && (parent.right - parent.left) < frame.width / 2 &&
            listOf(parent.text, parent.description).all { it.isBlank() || labels.any { label -> label.equals(it.trim(), true) } }
        }.maxByOrNull { it.id.length }
    }.distinctBy { listOf(it.left, it.top, it.right, it.bottom) }.singleOrNull()
}
