package fr.perso.tamligne

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Historique des perturbations sur les arrêts suivis, regroupé par jour. */
class HistoryActivity : Activity() {

    private lateinit var content: LinearLayout
    private var filter: String? = null
    private val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE)
    private val dayShort = SimpleDateFormat("EEE d MMM", Locale.FRANCE)
    private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Historique des perturbations"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        content = Ui.scrollScreen(this)
        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Effacer l'historique")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> AlertDialog.Builder(this)
                .setMessage("Effacer tout l'historique des perturbations ?")
                .setPositiveButton("Effacer") { _, _ -> History.clear(this); render() }
                .setNegativeButton("Annuler", null).show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = a }
        val c2 = Calendar.getInstance().apply { timeInMillis = b }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun duration(ms: Long): String {
        val m = ms / 60000
        return when {
            m < 60 -> "$m min"
            m < 48 * 60 -> "${m / 60} h ${String.format(Locale.FRANCE, "%02d", m % 60)}"
            else -> "${m / 1440} j ${(m % 1440) / 60} h"
        }
    }

    private fun render() {
        content.removeAllViews()
        val all = History.load(this)
        val lastOk = Health.lastOkTime(this)
        val now = System.currentTimeMillis()

        // Filtre par ligne
        val targets = all.flatMap { it.targets }.toSortedSet()
        if (targets.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = filter?.let { "Filtre : $it" } ?: "Toutes les lignes"
            row.addView(Button(this).apply {
                text = "$label ▾"
                setOnClickListener {
                    val opts = listOf("Toutes les lignes") + targets.toList()
                    AlertDialog.Builder(this@HistoryActivity)
                        .setItems(opts.toTypedArray()) { _, i -> filter = if (i == 0) null else opts[i]; render() }
                        .show()
                }
            })
            content.addView(row)
        }

        val list = all.filter { e -> filter == null || filter in e.targets || "Tout le réseau" in e.targets }
        // Statistiques sur 30 jours
        val month = list.filter { now - it.firstSeen < 30L * 24 * 3600 * 1000 }
        val ongoing = list.count { History.isOngoing(it, lastOk) }
        content.addView(Ui.card(this, 0xFFE8F0FA.toInt()).apply {
            addView(Ui.text(context, "30 derniers jours : ${month.size} perturbation(s)", 15f, bold = true))
            addView(Ui.text(context, "En cours actuellement : $ongoing", 14f, if (ongoing > 0) Ui.RED else Ui.GREEN))
            if (month.isNotEmpty()) {
                val total = month.sumOf { (if (History.isOngoing(it, lastOk)) now else it.lastSeen) - it.firstSeen }
                addView(Ui.text(context, "Durée cumulée observée : ${duration(total)}", 13f, Ui.GREY))
            }
        })

        if (list.isEmpty()) {
            content.addView(Ui.text(this,
                "Aucune perturbation enregistrée pour l'instant.\n\nL'historique se remplit automatiquement à chaque " +
                    "vérification (toutes les 15 min environ, et à chaque ouverture de l'appli).", 14f, Ui.GREY)
                .apply { setPadding(Ui.dp(context, 6), Ui.dp(context, 16), Ui.dp(context, 6), 0) })
            return
        }

        var currentDay = 0L
        for (e in list) {
            if (currentDay == 0L || !sameDay(currentDay, e.firstSeen)) {
                currentDay = e.firstSeen
                content.addView(Ui.text(this, day.format(Date(e.firstSeen)).replaceFirstChar { it.uppercase() }, 13f, Ui.GREY, true)
                    .apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 14), 0, Ui.dp(context, 2)) })
            }
            content.addView(entryCard(e, lastOk, now))
        }
    }

    private fun entryCard(e: History.Entry, lastOk: Long, now: Long) = Ui.card(this).apply {
        val ongoing = History.isOngoing(e, lastOk)
        val status = when {
            ongoing -> "● En cours depuis ${duration(now - e.firstSeen)}"
            else -> "Terminée · durée observée ${duration(maxOf(e.lastSeen - e.firstSeen, 60_000))}"
        }
        addView(Ui.text(context, e.targets.joinToString(" · "), 12f, Ui.BLUE, true))
        addView(Ui.text(context, e.header.ifEmpty { "Perturbation" }, 15f, bold = true))
        addView(Ui.text(context, status, 12f, if (ongoing) Ui.RED else Ui.GREY, ongoing))
        val seen = if (sameDay(e.firstSeen, e.lastSeen)) "${hm.format(Date(e.firstSeen))} → ${hm.format(Date(e.lastSeen))}"
        else "${dayShort.format(Date(e.firstSeen))} ${hm.format(Date(e.firstSeen))} → ${dayShort.format(Date(e.lastSeen))} ${hm.format(Date(e.lastSeen))}"
        addView(Ui.text(context, "Observée : $seen" + if (e.notified) " · notifiée" else "", 12f, Ui.GREY))
        if (e.announcedStart > 0 || e.announcedEnd > 0) {
            val s = if (e.announcedStart > 0) "${dayShort.format(Date(e.announcedStart * 1000))} ${hm.format(Date(e.announcedStart * 1000))}" else "?"
            val en = if (e.announcedEnd > 0) "${dayShort.format(Date(e.announcedEnd * 1000))} ${hm.format(Date(e.announcedEnd * 1000))}" else "?"
            addView(Ui.text(context, "Période annoncée par la TaM : $s → $en", 12f, Ui.GREY))
        }
        if (e.description.isNotEmpty() && e.description != e.header) {
            val d = Ui.text(context, e.description, 13f, 0xFF444444.toInt()).apply {
                maxLines = 3
                setPadding(0, Ui.dp(context, 4), 0, 0)
            }
            var open = false
            setOnClickListener { open = !open; d.maxLines = if (open) Int.MAX_VALUE else 3 }
            addView(d)
        }
        if (e.url.startsWith("http")) {
            addView(Ui.text(context, "Plus d'infos", 13f, Ui.BLUE, true).apply {
                setPadding(0, Ui.dp(context, 4), 0, 0)
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url))) }
            })
        }
    }
}
