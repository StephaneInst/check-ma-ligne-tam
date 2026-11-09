package fr.perso.tamligne

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Historique des perturbations ayant concerné les arrêts suivis.
 * Chaque alerte est enregistrée à sa première apparition, puis « vue pour la dernière fois »
 * à chaque vérification où elle est encore publiée : on connaît ainsi sa durée réelle.
 */
object History {
    private const val MAX_ENTRIES = 400
    private const val MAX_AGE_MS = 120L * 24 * 3600 * 1000 // 4 mois

    data class Entry(
        val key: String,
        val header: String,
        val description: String,
        val url: String,
        val targets: MutableSet<String>,
        val firstSeen: Long,
        var lastSeen: Long,
        var announcedStart: Long,
        var announcedEnd: Long,
        var wasUpcoming: Boolean,
        var notified: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("k", key).put("h", header).put("d", description).put("u", url)
            .put("t", JSONArray(targets.toList()))
            .put("f", firstSeen).put("l", lastSeen).put("as", announcedStart).put("ae", announcedEnd)
            .put("up", wasUpcoming).put("n", notified)

        companion object {
            fun fromJson(o: JSONObject): Entry {
                val t = o.optJSONArray("t") ?: JSONArray()
                val set = LinkedHashSet<String>()
                for (i in 0 until t.length()) set.add(t.getString(i))
                return Entry(
                    o.getString("k"), o.optString("h"), o.optString("d"), o.optString("u"), set,
                    o.optLong("f"), o.optLong("l"), o.optLong("as"), o.optLong("ae"),
                    o.optBoolean("up"), o.optBoolean("n"),
                )
            }
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    @Synchronized
    fun load(ctx: Context): MutableList<Entry> {
        val f = file(ctx)
        if (!f.exists()) return ArrayList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Entry>()
            for (i in 0 until arr.length()) try { out.add(Entry.fromJson(arr.getJSONObject(i))) } catch (_: Exception) {}
            out
        } catch (_: Exception) { ArrayList() }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
    }

    /** Enregistre les alertes trouvées lors d'une vérification. */
    @Synchronized
    fun record(ctx: Context, res: StatusResult, notifiedKeys: Set<String> = emptySet()) {
        val now = System.currentTimeMillis()
        val list = load(ctx)
        val byKey = list.associateBy { it.key }.toMutableMap()
        var changed = false
        for (s in res.statuses) for (a in s.alerts) {
            val target = if (a.scope == Scope.NETWORK) "Tout le réseau" else s.fav.lineLabel
            val periodStart = a.alert.periods.minOfOrNull { it.first } ?: 0L
            val periodEnd = a.alert.periods.maxOfOrNull { it.second } ?: 0L
            val e = byKey[a.alert.key]
            if (e == null) {
                val n = Entry(
                    a.alert.key, a.alert.header, a.alert.description, a.alert.url, linkedSetOf(target),
                    now, now, periodStart, periodEnd, a.upcoming, a.alert.key in notifiedKeys,
                )
                list.add(n); byKey[n.key] = n
            } else {
                e.lastSeen = now
                e.targets.add(target)
                e.announcedStart = periodStart
                e.announcedEnd = periodEnd
                if (a.alert.key in notifiedKeys) e.notified = true
            }
            changed = true
        }
        val before = list.size
        list.removeAll { now - it.lastSeen > MAX_AGE_MS }
        list.sortByDescending { it.firstSeen }
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        if (changed || list.size != before) save(ctx, list)
    }

    fun clear(ctx: Context) = synchronized(this) { file(ctx).delete() }

    /** Une alerte est « en cours » si elle était encore publiée lors de la dernière vérification réussie. */
    fun isOngoing(e: Entry, lastOkCheck: Long): Boolean = lastOkCheck > 0 && e.lastSeen >= lastOkCheck - 60_000
}
