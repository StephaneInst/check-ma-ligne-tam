package fr.perso.tamligne

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(
    val network: Network,
    val routeId: String,
    val routeShort: String,
    val isTram: Boolean,
    val color: Int,
    val textColor: Int,
    val directionId: Int,
    val headsign: String,
    val stopId: String,
    val stopName: String,
) {
    val key get() = "${network.name}|$routeId|$directionId|$stopId"
    val lineLabel get() = (if (isTram) "Tram " else "Bus ") + routeShort

    fun toJson(): JSONObject = JSONObject()
        .put("net", network.name).put("route", routeId).put("short", routeShort).put("tram", isTram)
        .put("color", color).put("text", textColor).put("dir", directionId).put("headsign", headsign)
        .put("stop", stopId).put("stopName", stopName)

    companion object {
        fun fromJson(o: JSONObject) = Favorite(
            Network.valueOf(o.getString("net")), o.getString("route"), o.getString("short"),
            o.optBoolean("tram"), o.getInt("color"), o.getInt("text"), o.getInt("dir"),
            o.getString("headsign"), o.getString("stop"), o.getString("stopName"),
        )
    }
}

object Prefs {
    private const val FILE = "tam"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun favorites(ctx: Context): List<Favorite> {
        val raw = sp(ctx).getString("favorites", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Favorite>()
        for (i in 0 until arr.length()) {
            try { out.add(Favorite.fromJson(arr.getJSONObject(i))) } catch (_: Exception) {}
        }
        return out
    }

    fun saveFavorites(ctx: Context, list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString("favorites", arr.toString()).apply()
    }

    fun addFavorite(ctx: Context, f: Favorite) {
        val list = favorites(ctx).filter { it.key != f.key } + f
        saveFavorites(ctx, list)
    }

    fun removeFavorite(ctx: Context, f: Favorite) {
        saveFavorites(ctx, favorites(ctx).filter { it.key != f.key })
    }

    /** Alertes déjà vues (affichées dans l'appli ou déjà notifiées). */
    @Synchronized
    fun seenAlerts(ctx: Context): Set<String> =
        HashSet(sp(ctx).getStringSet("seen", emptySet()) ?: emptySet())

    @Synchronized
    fun markSeen(ctx: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val s = seenAlerts(ctx).toMutableSet()
        if (s.addAll(keys)) sp(ctx).edit().putStringSet("seen", s).apply()
    }

    /** On oublie les alertes qui ne sont plus publiées, pour que le stockage ne grossisse pas. */
    @Synchronized
    fun pruneSeen(ctx: Context, stillPublished: Set<String>) {
        val s = seenAlerts(ctx)
        val kept = s.filter { it in stillPublished }.toSet()
        if (kept.size != s.size) sp(ctx).edit().putStringSet("seen", kept).apply()
    }

    fun notificationsOn(ctx: Context) = sp(ctx).getBoolean("notif", true)
    fun setNotificationsOn(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("notif", on).apply()
}
