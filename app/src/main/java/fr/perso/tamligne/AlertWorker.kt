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
import androidx.work.ExistingPeriodicWorkPolicy
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
        if (!Prefs.notificationsOn(ctx)) return Result.success()
        val favs = Prefs.favorites(ctx)
        if (favs.isEmpty()) return Result.success()
        return try {
            val res = Status.compute(ctx, favs, withDepartures = false)
            Notifier.notifyNew(ctx, res)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "tam-alertes"

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

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

    /** Envoie une notification pour chaque alerte pertinente qui n'a jamais été vue. */
    fun notifyNew(ctx: Context, res: StatusResult) {
        res.allPublishedKeys?.let { Prefs.pruneSeen(ctx, it) }
        val seen = Prefs.seenAlerts(ctx)

        // Une alerte peut concerner plusieurs favoris : on regroupe par alerte.
        val byKey = LinkedHashMap<String, Pair<AlertView, MutableList<Favorite>>>()
        for (s in res.statuses) for (a in s.alerts) {
            val k = a.alert.key
            if (k in seen) continue
            byKey.getOrPut(k) { Pair(a, ArrayList()) }.second.add(s.fav)
        }
        if (byKey.isEmpty()) return
        if (!canNotify(ctx)) return // on ne marque pas "vu" : elles seront notifiées une fois la permission accordée
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
    }
}
