package fr.perso.tamligne

import android.content.Context

enum class Scope(val label: String) { NETWORK("Tout le réseau"), LINE("Ligne"), STOP("Arrêt") }

data class AlertView(val alert: RtAlert, val scope: Scope, val upcoming: Boolean, val start: Long)

data class Departure(val tripId: String, val time: Long, val delaySec: Int?, val headsign: String)

data class FavStatus(
    val fav: Favorite,
    val alerts: List<AlertView>,
    val departures: List<Departure>,
    val alertsError: String?,
    val departuresError: String?,
)

data class StatusResult(
    val statuses: List<FavStatus>,
    /** Clés de toutes les alertes publiées, si tous les flux ont pu être lus (sinon null). */
    val allPublishedKeys: Set<String>?,
)

object Status {
    private const val UPCOMING_WINDOW_S = 3L * 24 * 3600

    /** Fonction pure : quelles alertes concernent ce favori ? */
    fun relevantAlerts(fav: Favorite, alerts: List<RtAlert>, nowS: Long): List<AlertView> {
        val favType = if (fav.isTram) 0 else 3
        val out = ArrayList<AlertView>()
        for (a in alerts) {
            // Période d'activité
            var active = a.periods.isEmpty()
            var nextStart = 0L
            for ((s, e) in a.periods) {
                val started = s == 0L || s <= nowS
                val notEnded = e == 0L || nowS < e
                if (started && notEnded) active = true
                else if (!started && s - nowS <= UPCOMING_WINDOW_S && (nextStart == 0L || s < nextStart)) nextStart = s
            }
            val upcoming = !active && nextStart > 0
            if (!active && !upcoming) continue

            var scope: Scope? = null
            for (e in a.entities) {
                val route = e.routeId.ifEmpty { e.trip?.routeId ?: "" }
                val s: Scope? = when {
                    e.stopId.isNotEmpty() && e.stopId == fav.stopId -> Scope.STOP
                    e.stopId.isNotEmpty() -> null
                    route.isNotEmpty() && route == fav.routeId &&
                        (e.directionId < 0 || e.directionId == fav.directionId) -> Scope.LINE
                    route.isNotEmpty() -> null
                    e.routeType >= 0 && e.routeType != favType -> null
                    else -> Scope.NETWORK // agence entière ou tout un mode (tram / bus)
                }
                if (s != null && (scope == null || s.ordinal > scope.ordinal)) scope = s
            }
            if (a.entities.isEmpty()) scope = Scope.NETWORK
            if (scope != null) out.add(AlertView(a, scope, upcoming, nextStart))
        }
        return out.sortedWith(compareBy<AlertView> { it.upcoming }.thenBy { it.scope.ordinal })
    }

    /** Fonction pure : prochains passages temps réel à l'arrêt du favori. */
    fun departures(
        fav: Favorite,
        updates: List<RtTripUpdate>,
        trips: Map<String, TripInfo>,
        nowS: Long,
        max: Int = 4,
    ): List<Departure> {
        val out = LinkedHashMap<String, Departure>()
        for (tu in updates) {
            val info = trips[tu.trip.tripId]
            val route = tu.trip.routeId.ifEmpty { info?.routeId ?: "" }
            if (route != fav.routeId) continue
            val dir = if (tu.trip.directionId >= 0) tu.trip.directionId else info?.directionId ?: -1
            if (dir >= 0 && dir != fav.directionId) continue
            val st = tu.stops.firstOrNull { it.stopId == fav.stopId && !it.skipped } ?: continue
            val time = if (st.departureTime > 0) st.departureTime else st.arrivalTime
            if (time <= 0 || time < nowS - 30) continue
            val delay = st.departureDelay ?: st.arrivalDelay
            val id = tu.trip.tripId.ifEmpty { "t$time" }
            if (id !in out) out[id] = Departure(id, time, delay, info?.headsign?.ifEmpty { null } ?: fav.headsign)
        }
        return out.values.sortedBy { it.time }.take(max)
    }

    private fun fetch(urls: List<String>): Pair<RtFeed?, String?> = try {
        Pair(GtfsRt.parse(Http.getFirst(urls)), null)
    } catch (e: Exception) {
        Pair(null, e.message ?: e.javaClass.simpleName)
    }

    /** Lit les flux temps réel et calcule l'état de chaque favori. Hors thread principal. */
    fun compute(ctx: Context, favs: List<Favorite>, withDepartures: Boolean): StatusResult {
        val nowS = System.currentTimeMillis() / 1000
        val nets = favs.map { it.network }.toSet()
        val alertFeeds = HashMap<Network, Pair<RtFeed?, String?>>()
        val tuFeeds = HashMap<Network, Pair<RtFeed?, String?>>()
        for (n in nets) {
            alertFeeds[n] = fetch(n.alertsUrls)
            if (withDepartures) tuFeeds[n] = fetch(n.tripUpdatesUrls)
        }
        val statuses = favs.map { f ->
            val (af, aerr) = alertFeeds[f.network] ?: Pair(null, "?")
            val alerts = af?.let { relevantAlerts(f, it.alerts, nowS) } ?: emptyList()
            var deps = emptyList<Departure>()
            var derr: String? = null
            if (withDepartures) {
                val (tf, terr) = tuFeeds[f.network] ?: Pair(null, "?")
                derr = terr
                if (tf != null) deps = departures(f, tf.tripUpdates, GtfsRepository.trips(ctx, f.network), nowS)
            }
            FavStatus(f, alerts, deps, aerr, derr)
        }
        val allOk = alertFeeds.values.all { it.first != null }
        val keys = if (allOk) alertFeeds.values.flatMap { p -> p.first!!.alerts.map { it.key } }.toSet() else null
        return StatusResult(statuses, keys)
    }
}
