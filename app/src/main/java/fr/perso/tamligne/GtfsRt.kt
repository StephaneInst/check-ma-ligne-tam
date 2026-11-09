package fr.perso.tamligne

/*
 * Décodeur minimal du format GTFS-Realtime (protobuf), sans dépendance.
 * On ne lit que les champs utiles : alertes (Alert) et mises à jour de trajets (TripUpdate).
 * Référence des numéros de champs : https://gtfs.org/realtime/reference/
 */

class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    fun hasMore(): Boolean = pos < end

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw IllegalStateException("varint tronqué")
            val b = buf[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalStateException("varint invalide")
        }
    }

    /** Retourne (numéro de champ, type de fil). */
    fun readTag(): Pair<Int, Int> {
        val tag = readVarint().toInt()
        return Pair(tag ushr 3, tag and 7)
    }

    private fun readLength(): Int {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > end) throw IllegalStateException("longueur invalide")
        return len
    }

    fun readMessage(): ProtoReader {
        val len = readLength()
        val r = ProtoReader(buf, pos, pos + len)
        pos += len
        return r
    }

    fun readString(): String {
        val len = readLength()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> { val len = readLength(); pos += len } // lire la longueur AVANT de déplacer pos
            5 -> pos += 4
            else -> throw IllegalStateException("type de fil inconnu $wireType")
        }
    }
}

data class TripDesc(
    val tripId: String = "",
    val routeId: String = "",
    val directionId: Int = -1,
)

data class InformedEntity(
    val agencyId: String = "",
    val routeId: String = "",
    val routeType: Int = -1,
    val stopId: String = "",
    val trip: TripDesc? = null,
    val directionId: Int = -1,
)

data class RtAlert(
    val id: String,
    val periods: List<Pair<Long, Long>>,
    val entities: List<InformedEntity>,
    val header: String,
    val description: String,
    val url: String,
    val effect: Int,
) {
    /** Clé stable pour savoir si on a déjà notifié cette alerte. */
    val key: String get() = id + "|" + header.hashCode() + "|" + description.hashCode()
}

data class StopTimeUpd(
    val stopId: String,
    val stopSequence: Int,
    val arrivalTime: Long,
    val arrivalDelay: Int?,
    val departureTime: Long,
    val departureDelay: Int?,
    val skipped: Boolean,
)

data class RtTripUpdate(
    val trip: TripDesc,
    val stops: List<StopTimeUpd>,
)

data class RtFeed(
    val timestamp: Long,
    val alerts: List<RtAlert>,
    val tripUpdates: List<RtTripUpdate>,
)

object GtfsRt {

    fun parse(bytes: ByteArray): RtFeed {
        val r = ProtoReader(bytes)
        var timestamp = 0L
        val alerts = ArrayList<RtAlert>()
        val updates = ArrayList<RtTripUpdate>()
        while (r.hasMore()) {
            val (field, wt) = r.readTag()
            when {
                field == 1 && wt == 2 -> timestamp = parseHeader(r.readMessage())
                field == 2 && wt == 2 -> {
                    val sub = r.readMessage()
                    // Une entité mal formée ne doit pas faire perdre tout le flux
                    try { parseEntity(sub, alerts, updates) } catch (_: Exception) {}
                }
                else -> r.skip(wt)
            }
        }
        return RtFeed(timestamp, alerts, updates)
    }

    private fun parseHeader(r: ProtoReader): Long {
        var ts = 0L
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            if (f == 3 && wt == 0) ts = r.readVarint() else r.skip(wt)
        }
        return ts
    }

    private fun parseEntity(r: ProtoReader, alerts: MutableList<RtAlert>, updates: MutableList<RtTripUpdate>) {
        var id = ""
        var deleted = false
        var alertReader: ProtoReader? = null
        var tuReader: ProtoReader? = null
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 2 -> id = r.readString()
                f == 2 && wt == 0 -> deleted = r.readVarint() != 0L
                f == 3 && wt == 2 -> tuReader = r.readMessage()
                f == 5 && wt == 2 -> alertReader = r.readMessage()
                else -> r.skip(wt)
            }
        }
        if (deleted) return
        alertReader?.let { alerts.add(parseAlert(id, it)) }
        tuReader?.let { updates.add(parseTripUpdate(it)) }
    }

    private fun parseAlert(id: String, r: ProtoReader): RtAlert {
        val periods = ArrayList<Pair<Long, Long>>()
        val entities = ArrayList<InformedEntity>()
        var header = ""
        var desc = ""
        var url = ""
        var effect = 0
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 2 -> periods.add(parseTimeRange(r.readMessage()))
                f == 5 && wt == 2 -> entities.add(parseEntitySelector(r.readMessage()))
                f == 7 && wt == 0 -> effect = r.readVarint().toInt()
                f == 8 && wt == 2 -> url = parseTranslated(r.readMessage())
                f == 10 && wt == 2 -> header = parseTranslated(r.readMessage())
                f == 11 && wt == 2 -> desc = parseTranslated(r.readMessage())
                else -> r.skip(wt)
            }
        }
        return RtAlert(id, periods, entities, header.trim(), desc.trim(), url.trim(), effect)
    }

    private fun parseTimeRange(r: ProtoReader): Pair<Long, Long> {
        var start = 0L
        var end = 0L
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 0 -> start = r.readVarint()
                f == 2 && wt == 0 -> end = r.readVarint()
                else -> r.skip(wt)
            }
        }
        return Pair(start, end)
    }

    private fun parseEntitySelector(r: ProtoReader): InformedEntity {
        var agency = ""
        var route = ""
        var routeType = -1
        var stop = ""
        var trip: TripDesc? = null
        var dir = -1
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 2 -> agency = r.readString()
                f == 2 && wt == 2 -> route = r.readString()
                f == 3 && wt == 0 -> routeType = r.readVarint().toInt()
                f == 4 && wt == 2 -> trip = parseTripDesc(r.readMessage())
                f == 5 && wt == 2 -> stop = r.readString()
                f == 6 && wt == 0 -> dir = r.readVarint().toInt()
                else -> r.skip(wt)
            }
        }
        return InformedEntity(agency, route, routeType, stop, trip, dir)
    }

    private fun parseTripDesc(r: ProtoReader): TripDesc {
        var tripId = ""
        var routeId = ""
        var dir = -1
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 2 -> tripId = r.readString()
                f == 5 && wt == 2 -> routeId = r.readString()
                f == 6 && wt == 0 -> dir = r.readVarint().toInt()
                else -> r.skip(wt)
            }
        }
        return TripDesc(tripId, routeId, dir)
    }

    /** TranslatedString : on préfère le français, sinon la première traduction. */
    private fun parseTranslated(r: ProtoReader): String {
        var first: String? = null
        var fr: String? = null
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            if (f == 1 && wt == 2) {
                val t = r.readMessage()
                var text = ""
                var lang = ""
                while (t.hasMore()) {
                    val (tf, twt) = t.readTag()
                    when {
                        tf == 1 && twt == 2 -> text = t.readString()
                        tf == 2 && twt == 2 -> lang = t.readString()
                        else -> t.skip(twt)
                    }
                }
                if (first == null) first = text
                if (fr == null && lang.lowercase().startsWith("fr")) fr = text
            } else {
                r.skip(wt)
            }
        }
        return fr ?: first ?: ""
    }

    private fun parseTripUpdate(r: ProtoReader): RtTripUpdate {
        var trip = TripDesc()
        val stops = ArrayList<StopTimeUpd>()
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 2 -> trip = parseTripDesc(r.readMessage())
                f == 2 && wt == 2 -> stops.add(parseStopTimeUpdate(r.readMessage()))
                else -> r.skip(wt)
            }
        }
        return RtTripUpdate(trip, stops)
    }

    private fun parseStopTimeUpdate(r: ProtoReader): StopTimeUpd {
        var seq = -1
        var stopId = ""
        var arr: Pair<Long, Int?> = Pair(0L, null)
        var dep: Pair<Long, Int?> = Pair(0L, null)
        var skipped = false
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 0 -> seq = r.readVarint().toInt()
                f == 2 && wt == 2 -> arr = parseStopTimeEvent(r.readMessage())
                f == 3 && wt == 2 -> dep = parseStopTimeEvent(r.readMessage())
                f == 4 && wt == 2 -> stopId = r.readString()
                f == 5 && wt == 0 -> skipped = r.readVarint() == 1L // SKIPPED
                else -> r.skip(wt)
            }
        }
        return StopTimeUpd(stopId, seq, arr.first, arr.second, dep.first, dep.second, skipped)
    }

    private fun parseStopTimeEvent(r: ProtoReader): Pair<Long, Int?> {
        var time = 0L
        var delay: Int? = null
        while (r.hasMore()) {
            val (f, wt) = r.readTag()
            when {
                f == 1 && wt == 0 -> delay = r.readVarint().toInt()
                f == 2 && wt == 0 -> time = r.readVarint()
                else -> r.skip(wt)
            }
        }
        return Pair(time, delay)
    }
}
