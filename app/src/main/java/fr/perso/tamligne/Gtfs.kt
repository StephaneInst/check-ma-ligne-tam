package fr.perso.tamligne

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/** Les deux réseaux publiés en open data par Montpellier Méditerranée Métropole. */
enum class Network(val label: String, val baseUrl: String) {
    URBAIN("Urbain", "https://data.montpellier3m.fr/GTFS/Urbain/"),
    SUBURBAIN("Suburbain", "https://data.montpellier3m.fr/GTFS/Suburbain/");

    val gtfsUrl get() = baseUrl + "GTFS.zip"
    val alertsUrl get() = baseUrl + "Alert.pb"
    val tripUpdatesUrl get() = baseUrl + "TripUpdate.pb"
}

data class StopRef(val id: String, val name: String)

data class Direction(val id: Int, val headsign: String, val stops: List<StopRef>)

data class Line(
    val network: Network,
    val id: String,
    val shortName: String,
    val longName: String,
    val color: Int,
    val textColor: Int,
    val type: Int,
    val directions: List<Direction>,
) {
    val isTram get() = type == 0
    val label: String
        get() = (if (isTram) "Tram " else "Bus ") + shortName
}

data class TripInfo(val routeId: String, val directionId: Int, val headsign: String)

object Http {
    fun get(url: String, timeoutMs: Int = 20000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "MaLigneTaM/1.0 (Android)")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code pour $url")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "MaLigneTaM/1.0 (Android)")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code pour $url")
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            c.disconnect()
        }
    }
}

object Csv {
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++
                    } else inQuotes = false
                } else sb.append(ch)
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> { out.add(sb.toString()); sb.setLength(0) }
                    '\r' -> {}
                    else -> sb.append(ch)
                }
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** Parcourt un fichier CSV GTFS ligne à ligne, en donnant accès aux colonnes par nom. */
    fun forEach(reader: BufferedReader, block: (get: (String) -> String) -> Unit) {
        val headerLine = reader.readLine() ?: return
        val header = parseLine(headerLine.removePrefix("﻿")).map { it.trim() }
        val idx = HashMap<String, Int>()
        header.forEachIndexed { i, h -> idx[h] = i }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cols = parseLine(line)
            block { name ->
                val i = idx[name]
                if (i == null || i >= cols.size) "" else cols[i].trim()
            }
        }
    }
}

/**
 * Télécharge le GTFS (horaires de référence) de chaque réseau, en extrait la liste des lignes,
 * de leurs sens et de leurs arrêts, et la garde en cache sur le téléphone.
 */
object GtfsRepository {
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private fun zipFile(ctx: Context, n: Network) = File(ctx.filesDir, "gtfs_${n.name}.zip")
    private fun linesFile(ctx: Context, n: Network) = File(ctx.filesDir, "lines_${n.name}.json")
    private fun tripsFile(ctx: Context, n: Network) = File(ctx.filesDir, "trips_${n.name}.tsv")

    @Volatile private var linesCache = HashMap<Network, List<Line>>()
    @Volatile private var tripsCache = HashMap<Network, Map<String, TripInfo>>()

    fun hasData(ctx: Context, n: Network) = linesFile(ctx, n).exists() && tripsFile(ctx, n).exists()

    fun isStale(ctx: Context, n: Network): Boolean {
        val f = linesFile(ctx, n)
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS
    }

    /** Télécharge et reconstruit les données d'un réseau. À appeler hors du thread principal. */
    @Synchronized
    fun refresh(ctx: Context, n: Network) {
        val zip = zipFile(ctx, n)
        Http.download(n.gtfsUrl, zip)
        build(n, zip, linesFile(ctx, n), tripsFile(ctx, n))
        zip.delete() // on ne garde que le résumé, beaucoup plus léger
        linesCache.remove(n)
        tripsCache.remove(n)
    }

    fun ensure(ctx: Context, n: Network) {
        if (!hasData(ctx, n)) refresh(ctx, n)
        else if (isStale(ctx, n)) {
            try { refresh(ctx, n) } catch (_: Exception) { /* on garde l'ancienne version */ }
        }
    }

    fun lines(ctx: Context, n: Network): List<Line> {
        linesCache[n]?.let { return it }
        val f = linesFile(ctx, n)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val out = ArrayList<Line>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dirs = o.getJSONArray("dirs")
            val dl = ArrayList<Direction>()
            for (j in 0 until dirs.length()) {
                val d = dirs.getJSONObject(j)
                val st = d.getJSONArray("stops")
                val sl = ArrayList<StopRef>()
                for (k in 0 until st.length()) {
                    val s = st.getJSONArray(k)
                    sl.add(StopRef(s.getString(0), s.getString(1)))
                }
                dl.add(Direction(d.getInt("id"), d.getString("headsign"), sl))
            }
            out.add(
                Line(
                    n, o.getString("id"), o.getString("short"), o.getString("long"),
                    o.getInt("color"), o.getInt("text"), o.getInt("type"), dl
                )
            )
        }
        linesCache[n] = out
        return out
    }

    fun trips(ctx: Context, n: Network): Map<String, TripInfo> {
        tripsCache[n]?.let { return it }
        val f = tripsFile(ctx, n)
        if (!f.exists()) return emptyMap()
        val m = HashMap<String, TripInfo>()
        f.forEachLine { l ->
            val p = l.split('\t')
            if (p.size >= 4) m[p[0]] = TripInfo(p[1], p[2].toIntOrNull() ?: -1, p[3])
        }
        tripsCache[n] = m
        return m
    }

    private fun parseColor(hex: String, default: Int): Int {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6) return default
        return try { (0xFF000000.toInt()) or h.toInt(16) } catch (_: NumberFormatException) { default }
    }

    /** Analyse le zip GTFS. Fonction pure (testable hors Android). */
    fun build(n: Network, zip: File, linesOut: File, tripsOut: File) {
        ZipFile(zip).use { z ->
            fun reader(name: String): BufferedReader? {
                val e = z.getEntry(name) ?: z.entries().asSequence().firstOrNull { it.name.endsWith("/$name") }
                ?: return null
                return BufferedReader(InputStreamReader(z.getInputStream(e), Charsets.UTF_8), 1 shl 16)
            }

            // Arrêts
            val stopNames = HashMap<String, String>()
            reader("stops.txt")?.use { r ->
                Csv.forEach(r) { g -> stopNames[g("stop_id")] = g("stop_name") }
            }

            // Lignes
            data class R(val id: String, val short: String, val long: String, val type: Int, val color: Int, val text: Int)
            val routes = LinkedHashMap<String, R>()
            reader("routes.txt")?.use { r ->
                Csv.forEach(r) { g ->
                    val id = g("route_id")
                    routes[id] = R(
                        id,
                        g("route_short_name").ifEmpty { id },
                        g("route_long_name"),
                        g("route_type").toIntOrNull() ?: 3,
                        parseColor(g("route_color"), 0xFF0B5FA5.toInt()),
                        parseColor(g("route_text_color"), 0xFFFFFFFF.toInt()),
                    )
                }
            }

            // Trajets
            val trips = HashMap<String, TripInfo>()
            reader("trips.txt")?.use { r ->
                Csv.forEach(r) { g ->
                    trips[g("trip_id")] = TripInfo(g("route_id"), g("direction_id").toIntOrNull() ?: 0, g("trip_headsign"))
                }
            }
            tripsOut.bufferedWriter().use { w ->
                for ((id, t) in trips) {
                    w.write(id); w.write("\t"); w.write(t.routeId); w.write("\t")
                    w.write(t.directionId.toString()); w.write("\t")
                    w.write(t.headsign.replace('\t', ' ')); w.write("\n")
                }
            }

            // Passe 1 : nombre d'arrêts par trajet -> on retient, pour chaque (ligne, sens),
            // le trajet qui dessert le plus d'arrêts (le parcours "complet").
            val counts = HashMap<String, Int>()
            reader("stop_times.txt")?.use { r ->
                Csv.forEach(r) { g -> val t = g("trip_id"); counts[t] = (counts[t] ?: 0) + 1 }
            }
            val best = HashMap<String, Pair<String, Int>>() // "route|dir" -> (trip, nb)
            for ((tripId, c) in counts) {
                val t = trips[tripId] ?: continue
                val key = t.routeId + "|" + t.directionId
                val cur = best[key]
                if (cur == null || c > cur.second) best[key] = Pair(tripId, c)
            }
            val wanted = best.values.map { it.first }.toHashSet()

            // Passe 2 : séquence d'arrêts des trajets retenus
            val seqs = HashMap<String, MutableList<Pair<Int, String>>>()
            reader("stop_times.txt")?.use { r ->
                Csv.forEach(r) { g ->
                    val t = g("trip_id")
                    if (t in wanted) {
                        seqs.getOrPut(t) { ArrayList() }
                            .add(Pair(g("stop_sequence").toIntOrNull() ?: 0, g("stop_id")))
                    }
                }
            }

            // Destination la plus fréquente par (ligne, sens)
            val headsignCounts = HashMap<String, HashMap<String, Int>>()
            for (t in trips.values) {
                if (t.headsign.isEmpty()) continue
                val m = headsignCounts.getOrPut(t.routeId + "|" + t.directionId) { HashMap() }
                m[t.headsign] = (m[t.headsign] ?: 0) + 1
            }

            val out = JSONArray()
            for (route in routes.values) {
                val dirs = JSONArray()
                for (dir in 0..1) {
                    val key = route.id + "|" + dir
                    val trip = best[key]?.first ?: continue
                    val stops = seqs[trip]?.sortedBy { it.first } ?: continue
                    val st = JSONArray()
                    val seen = HashSet<String>()
                    for ((_, sid) in stops) {
                        if (!seen.add(sid)) continue
                        st.put(JSONArray().put(sid).put(stopNames[sid] ?: sid))
                    }
                    val headsign = headsignCounts[key]?.maxByOrNull { it.value }?.key
                        ?: stopNames[stops.last().second] ?: "Sens ${dir + 1}"
                    dirs.put(JSONObject().put("id", dir).put("headsign", headsign).put("stops", st))
                }
                if (dirs.length() == 0) continue
                out.put(
                    JSONObject()
                        .put("id", route.id).put("short", route.short).put("long", route.long)
                        .put("type", route.type).put("color", route.color).put("text", route.text)
                        .put("dirs", dirs)
                )
            }
            linesOut.writeText(out.toString())
        }
    }
}
