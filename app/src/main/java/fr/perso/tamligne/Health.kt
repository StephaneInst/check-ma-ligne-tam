package fr.perso.tamligne

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Suivi de la fiabilité : chaque vérification (automatique ou dans l'appli) est journalisée,
 * avec l'âge des données publiées par la TaM. Permet de savoir si la surveillance tourne vraiment.
 */
object Health {
    private const val FILE = "health"
    private const val MAX_CHECKS = 80

    data class Check(
        val time: Long,
        /** "auto" (tâche de fond périodique), "test" (tâche de fond lancée à la main), "appli" (écran ouvert). */
        val kind: String,
        val ok: Boolean,
        val alerts: Int,
        val msg: String,
    )

    enum class Level { OK, WARN, BAD, NEUTRAL }

    data class Summary(val level: Level, val title: String, val details: List<String>)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun recordCheck(ctx: Context, kind: String, res: StatusResult?, error: String?) {
        val now = System.currentTimeMillis()
        val ok = res != null && res.alertErrors.isEmpty()
        val nAlerts = res?.statuses?.flatMap { s -> s.alerts.map { it.alert.key } }?.toSet()?.size ?: 0
        val msg = when {
            error != null -> error
            res == null -> "échec"
            res.alertErrors.isNotEmpty() -> res.alertErrors.entries.joinToString(" ; ") { "${it.key.label} : ${it.value}" }
            else -> ""
        }
        val arr = try { JSONArray(sp(ctx).getString("checks", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("t", now).put("k", kind).put("ok", ok).put("n", nAlerts).put("m", msg.take(300)))
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX_CHECKS)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        val e = sp(ctx).edit().putString("checks", trimmed.toString())
        if (ok) e.putLong("lastOk", now)
        res?.feedTimestamps?.forEach { (n, ts) -> if (ts > 0) e.putLong("feedTs_${n.name}", ts) }
        res?.sources?.forEach { (n, host) -> e.putString("src_${n.name}", host) }
        e.apply()
    }

    fun checks(ctx: Context): List<Check> {
        val arr = try { JSONArray(sp(ctx).getString("checks", "[]")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Check>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Check(o.optLong("t"), o.optString("k", if (o.optBoolean("a")) "auto" else "appli"), o.optBoolean("ok"), o.optInt("n"), o.optString("m")))
        }
        return out.reversed()
    }

    fun lastOkTime(ctx: Context) = sp(ctx).getLong("lastOk", 0L)
    fun feedTs(ctx: Context, n: Network) = sp(ctx).getLong("feedTs_${n.name}", 0L)
    fun source(ctx: Context, n: Network) = sp(ctx).getString("src_${n.name}", null)

    fun notificationsAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false

    fun ago(ms: Long): String {
        val m = ms / 60000
        return when {
            m < 1 -> "à l'instant"
            m < 60 -> "il y a $m min"
            m < 48 * 60 -> "il y a ${m / 60} h ${if (m % 60 > 0) "${m % 60} min" else ""}".trim()
            else -> "il y a ${m / 1440} jours"
        }
    }

    fun summary(ctx: Context): Summary {
        val now = System.currentTimeMillis()
        val favs = Prefs.favorites(ctx)
        if (favs.isEmpty()) return Summary(Level.NEUTRAL, "Ajoute un arrêt pour activer la surveillance", emptyList())

        val details = ArrayList<String>()
        var level = Level.OK
        fun worse(l: Level) { if (l.ordinal > level.ordinal && l != Level.NEUTRAL) level = l }

        // 1. Notifications
        if (!Prefs.notificationsOn(ctx)) { worse(Level.BAD); details.add("✗ Notifications désactivées dans l'appli (menu ⋮)") }
        else if (!notificationsAllowed(ctx)) { worse(Level.BAD); details.add("✗ Notifications refusées dans les réglages Android") }
        else details.add("✓ Notifications autorisées")

        // 2. Vérifications automatiques
        val lastAuto = checks(ctx).firstOrNull { it.kind == "auto" }
        val installed = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime } catch (_: Exception) { now }
        if (lastAuto == null) {
            if (now - installed < 40 * 60000) details.add("… Première vérification automatique dans les 15 prochaines minutes")
            else { worse(Level.BAD); details.add("✗ Aucune vérification automatique n'a encore tourné") }
        } else {
            val age = now - lastAuto.time
            val txt = "vérification automatique ${ago(age)}" + if (lastAuto.ok) "" else " (en échec : ${lastAuto.msg})"
            when {
                age > 3 * 3600000 -> { worse(Level.BAD); details.add("✗ Dernière $txt") }
                age > 40 * 60000 || !lastAuto.ok -> { worse(Level.WARN); details.add("! Dernière $txt") }
                else -> details.add("✓ Dernière $txt")
            }
        }

        // 3. Fraîcheur des données TaM
        for (n in favs.map { it.network }.toSet()) {
            val ts = feedTs(ctx, n) * 1000
            if (ts <= 0) continue
            val age = now - ts
            // Le flux est normalement régénéré en continu : au-delà de 30 min, il est probablement figé.
            if (age > 30 * 60000) { worse(Level.WARN); details.add("! Flux TaM ${n.label} non mis à jour depuis ${ago(age).removePrefix("il y a ")}") }
            else details.add("✓ Données TaM ${n.label} publiées ${ago(age)}")
        }

        // 4. Batterie
        if (!batteryUnrestricted(ctx)) {
            worse(Level.WARN)
            details.add("! Économie de batterie active : Android peut retarder les vérifications")
        } else details.add("✓ Aucune restriction de batterie")

        val title = when (level) {
            Level.OK -> "Surveillance active"
            Level.WARN -> "Surveillance à vérifier"
            Level.BAD -> "Surveillance interrompue"
            Level.NEUTRAL -> ""
        }
        return Summary(level, title, details)
    }
}
