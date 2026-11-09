package fr.perso.tamligne

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Vérifie les perturbations en arrière-plan (toutes les 15 min, minimum imposé par Android). */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val kind = if (inputData.getBoolean(KEY_TEST, false)) "test" else "auto"
        val favs = Prefs.favorites(ctx)
        if (favs.isEmpty()) return Result.success()
        return try {
            val res = Status.compute(ctx, favs, withDepartures = false)
            val notified = if (Prefs.notificationsOn(ctx)) Notifier.notifyNew(ctx, res) else emptySet()
            History.record(ctx, res, notified)
            Health.recordCheck(ctx, kind, res, null)
            if (kind == "test") Notifier.testResult(ctx, res)
            Result.success()
        } catch (e: Exception) {
            Health.recordCheck(ctx, kind, null, e.message ?: e.javaClass.simpleName)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "tam-alertes"
        private const val KEY_TEST = "test"

        /** Lance tout de suite une vérification en arrière-plan, par le même chemin que la tâche automatique. */
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequest.Builder(AlertWorker::class.java)
                .setInputData(Data.Builder().putBoolean(KEY_TEST, true).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("tam-test", ExistingWorkPolicy.REPLACE, req)
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequest.Builder(AlertWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Notifier {
    private const val CHANNEL = "perturbations"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Perturbations TaM", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Alertes de trafic sur vos lignes favorites"
            nm.createNotificationChannel(ch)
        }
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notification de test : prouve que la chaîne complète (tâche de fond + notification) fonctionne. */
    fun testResult(ctx: Context, res: StatusResult) {
        if (!canNotify(ctx)) return
        ensureChannel(ctx)
        val nAlerts = res.statuses.flatMap { s -> s.alerts.map { it.alert.key } }.toSet().size
        val body = if (res.alertErrors.isEmpty())
            "Vérification en arrière-plan réussie. Perturbations en cours sur tes arrêts : $nAlerts."
        else "Vérification faite, mais lecture en échec : " + res.alertErrors.values.joinToString(" ; ")
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Test Ma ligne TaM ✓")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(424242, n)
    }

    /** Envoie une notification pour chaque alerte pertinente jamais vue. Retourne les clés notifiées. */
    fun notifyNew(ctx: Context, res: StatusResult): Set<String> {
        res.allPublishedKeys?.let { Prefs.pruneSeen(ctx, it) }
        val seen = Prefs.seenAlerts(ctx)

        // Une alerte peut concerner plusieurs favoris : on regroupe par alerte.
        val byKey = LinkedHashMap<String, Pair<AlertView, MutableList<Favorite>>>()
        for (s in res.statuses) for (a in s.alerts) {
            val k = a.alert.key
            if (k in seen) continue
            byKey.getOrPut(k) { Pair(a, ArrayList()) }.second.add(s.fav)
        }
        if (byKey.isEmpty()) return emptySet()
        if (!canNotify(ctx)) return emptySet() // on ne marque pas "vu" : elles seront notifiées une fois la permission accordée
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = openApp(ctx)
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE)
        for ((key, pair) in byKey) {
            val (av, favs) = pair
            val who = if (av.scope == Scope.NETWORK) "Réseau TaM"
            else favs.map { it.lineLabel }.distinct().joinToString(", ")
            val prefix = if (av.upcoming) "À venir (${fmt.format(Date(av.start * 1000))}) – " else ""
            val title = "$who : ${av.alert.header.ifEmpty { "Perturbation" }}"
            val body = prefix + av.alert.description.ifEmpty { av.alert.header }
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(key.hashCode(), n)
        }
        Prefs.markSeen(ctx, byKey.keys)
        return byKey.keys
    }
}
