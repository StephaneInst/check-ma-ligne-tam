package fr.perso.tamligne

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Écran « Fiabilité » : la surveillance tourne-t-elle vraiment ? Les données sont-elles fraîches ? */
class HealthActivity : Activity() {

    private lateinit var content: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE)
    private val tick = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Fiabilité"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        content = Ui.scrollScreen(this)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun render() {
        content.removeAllViews()
        val s = Health.summary(this)

        // État global
        content.addView(Ui.card(this, Ui.levelBg(s.level)).apply {
            addView(Ui.text(context, s.title.ifEmpty { "Surveillance" }, 18f, Ui.levelColor(s.level), true))
            for (d in s.details) addView(Ui.text(context, d, 14f).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
        })

        // Actions
        content.addView(Ui.card(this).apply {
            addView(Ui.text(context, "Tester", 15f, bold = true))
            addView(Ui.text(context,
                "Lance une vérification en arrière-plan, exactement comme la tâche automatique, puis t'envoie une " +
                    "notification de test. Si tu la reçois, toute la chaîne fonctionne.", 13f, Ui.GREY))
            addView(button("Lancer un test complet") {
                if (Prefs.favorites(this@HealthActivity).isEmpty()) {
                    Toast.makeText(this@HealthActivity, "Ajoute d'abord un arrêt", Toast.LENGTH_SHORT).show()
                } else if (!Health.notificationsAllowed(this@HealthActivity)) {
                    openNotificationSettings()
                } else {
                    AlertWorker.runNow(this@HealthActivity)
                    Toast.makeText(this@HealthActivity, "Test lancé : notification dans quelques secondes", Toast.LENGTH_LONG).show()
                }
            })
            if (!Health.notificationsAllowed(this@HealthActivity)) {
                addView(button("Autoriser les notifications") { openNotificationSettings() })
            }
            if (!Health.batteryUnrestricted(this@HealthActivity)) {
                addView(Ui.text(context,
                    "Pour des alertes à l'heure, retire les restrictions de batterie de l'appli " +
                        "(Batterie → « Aucune restriction » / « Non optimisée »).", 13f, Ui.GREY)
                    .apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
                addView(button("Ouvrir les réglages de batterie") {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        openAppSettings()
                    }
                })
            }
        })

        // Sources de données
        content.addView(Ui.card(this).apply {
            addView(Ui.text(context, "Sources des données", 15f, bold = true))
            for (n in Network.values()) {
                val src = Health.source(context, n) ?: "pas encore contactée"
                val ts = Health.feedTs(context, n)
                val age = if (ts > 0) " · publiées ${Health.ago(System.currentTimeMillis() - ts * 1000)}" else ""
                addView(Ui.text(context, "${n.label} : $src$age", 13f, Ui.GREY))
            }
            addView(Ui.text(context,
                "Données officielles publiées par la TaM / Montpellier Méditerranée Métropole en open data " +
                    "(licence ODbL). En cas de doute, compare avec le site de la TaM.",
                12f, Ui.GREY).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
            addView(button("Comparer avec tam-voyages.com") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tam-voyages.com")))
            })
        })

        // Journal
        val checks = Health.checks(this)
        content.addView(Ui.card(this).apply {
            addView(Ui.text(context, "Journal des vérifications", 15f, bold = true))
            if (checks.isEmpty()) addView(Ui.text(context, "Aucune vérification pour l'instant.", 13f, Ui.GREY))
            for (c in checks.take(40)) {
                val who = when (c.kind) { "auto" -> "Auto"; "test" -> "Test"; else -> "Appli" }
                val res = if (c.ok) "✓ ${c.alerts} perturbation(s)" else "✗ ${c.msg}"
                addView(Ui.text(context, "${fmt.format(Date(c.time))} · $who · $res", 12f, if (c.ok) Ui.DARK else Ui.RED)
                    .apply { setPadding(0, Ui.dp(context, 3), 0, 0) })
            }
        })
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}
