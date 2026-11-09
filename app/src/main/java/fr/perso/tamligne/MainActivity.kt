package fr.perso.tamligne

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {

    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var footer: TextView
    private lateinit var progress: ProgressBar
    private lateinit var healthBanner: TextView
    private var lastStatuses: Map<String, FavStatus> = emptyMap()
    private var refreshing = false
    private val hhmm = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val dayHhmm = SimpleDateFormat("EEE d MMM 'à' HH:mm", Locale.FRANCE)

    private val autoRefresh = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 30_000)
        }
    }

    // ---------- Cycle de vie ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        buildUi()
        Notifier.ensureChannel(this)
        AlertWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        render()
        if (Prefs.favorites(this).isEmpty()) ui.post { startAdd() }
    }

    override fun onResume() {
        super.onResume()
        renderHealth()
        ui.removeCallbacks(autoRefresh)
        ui.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(autoRefresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdownNow()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Ajouter un arrêt")
        menu.add(0, 2, 1, "Actualiser")
        menu.add(0, 3, 2, "").isCheckable = true
        menu.add(0, 6, 3, "Historique des perturbations")
        menu.add(0, 7, 3, "Fiabilité / test")
        menu.add(0, 4, 3, "Mettre à jour la liste des lignes")
        menu.add(0, 5, 4, "Ouvrir tam-voyages.com")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(3)?.let {
            it.isChecked = Prefs.notificationsOn(this)
            it.title = "Notifications de perturbation"
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startAdd()
            2 -> refresh()
            3 -> {
                val on = !Prefs.notificationsOn(this)
                Prefs.setNotificationsOn(this, on)
                item.isChecked = on
                toastText(if (on) "Notifications activées" else "Notifications désactivées")
            }
            4 -> updateLineData()
            5 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tam-voyages.com")))
            6 -> startActivity(Intent(this, HistoryActivity::class.java))
            7 -> startActivity(Intent(this, HealthActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Interface ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: CharSequence, sizeSp: Float = 15f, color: Int = 0xFF222222.toInt(), bold: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F4F7.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        bar.addView(Button(this).apply {
            text = "+ Arrêt"
            isAllCaps = false
            setOnClickListener { startAdd() }
        })
        bar.addView(Button(this).apply {
            text = "Historique"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        progress = ProgressBar(this).apply { visibility = View.INVISIBLE }
        bar.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)))
        bar.addView(Button(this).apply {
            text = "↻"
            setOnClickListener { refresh() }
        })
        root.addView(bar)

        // Bandeau de fiabilité : un coup d'œil pour savoir si la surveillance tourne
        healthBanner = text("", 13f, bold = true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, HealthActivity::class.java)) }
        }
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.setMargins(dp(12), dp(4), dp(12), 0)
        root.addView(healthBanner, blp)

        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(list)
        footer = text("", 12f, 0xFF777777.toInt()).apply { setPadding(dp(6), dp(12), dp(6), 0) }
        inner.addView(footer)
        scroll.addView(inner)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun renderHealth() {
        val s = Health.summary(this)
        if (s.level == Health.Level.NEUTRAL) { healthBanner.visibility = View.GONE; return }
        healthBanner.visibility = View.VISIBLE
        val icon = when (s.level) { Health.Level.OK -> "✓"; Health.Level.WARN -> "!"; else -> "✗" }
        val firstIssue = s.details.firstOrNull { it.startsWith("✗") || it.startsWith("!") }
        val auto = s.details.firstOrNull { it.contains("vérification automatique") }
        healthBanner.text = "$icon ${s.title}" + ((firstIssue ?: auto)?.let { " · " + it.drop(2) } ?: "") + "  ›"
        healthBanner.setTextColor(Ui.levelColor(s.level))
        healthBanner.background = GradientDrawable().apply {
            setColor(Ui.levelBg(s.level))
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun render() {
        renderHealth()
        list.removeAllViews()
        val favs = Prefs.favorites(this)
        if (favs.isEmpty()) {
            list.addView(text(
                "Aucun arrêt suivi.\n\nAppuie sur « + Ajouter un arrêt », choisis ta ligne, le sens puis ton arrêt. " +
                    "Tu verras les prochains passages en temps réel et tu recevras une notification en cas de perturbation " +
                    "(grève, travaux, incident…).", 15f, 0xFF555555.toInt()
            ).apply { setPadding(dp(8), dp(24), dp(8), dp(8)) })
            return
        }
        for (f in favs) list.addView(card(f, lastStatuses[f.key]))
    }

    private fun card(f: Favorite, st: FavStatus?): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(2).toFloat()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, dp(6))
        c.layoutParams = lp

        // En-tête : pastille de ligne + arrêt + sens
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(f.routeShort, 16f, f.textColor, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(40)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(f.color)
                cornerRadius = dp(if (f.isTram) 14 else 6).toFloat()
            }
        })
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        names.addView(text(f.stopName, 17f, bold = true))
        names.addView(text("${f.lineLabel} → ${f.headsign}", 13f, 0xFF666666.toInt()))
        head.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(head)

        // État du trafic
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        when {
            st == null -> statusBox.addView(text("Chargement…", 14f, 0xFF888888.toInt()))
            st.alerts.isEmpty() && st.alertsError != null ->
                statusBox.addView(text("Infos trafic indisponibles pour le moment", 14f, 0xFF888888.toInt()))
            st.alerts.isEmpty() ->
                statusBox.addView(text("✓ Aucune perturbation signalée", 14f, 0xFF1E8E3E.toInt(), true))
            else -> for (a in st.alerts) statusBox.addView(alertView(a))
        }
        c.addView(statusBox)

        // Prochains passages
        val dep = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        if (st != null) {
            when {
                st.departures.isNotEmpty() -> {
                    dep.addView(text("Prochains passages", 13f, 0xFF666666.toInt(), true))
                    val nowS = System.currentTimeMillis() / 1000
                    for (d in st.departures) dep.addView(departureRow(d, nowS))
                }
                st.departuresError != null ->
                    dep.addView(text("Horaires temps réel indisponibles", 13f, 0xFF888888.toInt()))
                else ->
                    dep.addView(text("Pas de passage annoncé en temps réel pour l'instant", 13f, 0xFF888888.toInt()))
            }
        }
        c.addView(dep)

        c.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Retirer cet arrêt ?")
                .setMessage("${f.lineLabel} – ${f.stopName}\n→ ${f.headsign}")
                .setPositiveButton("Retirer") { _, _ ->
                    Prefs.removeFavorite(this, f)
                    render()
                }
                .setNegativeButton("Annuler", null)
                .show()
            true
        }
        return c
    }

    private fun alertView(a: AlertView): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(if (a.upcoming) 0xFFFFF4E0.toInt() else 0xFFFDECEA.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(6))
        box.layoutParams = lp
        val color = if (a.upcoming) 0xFFB26A00.toInt() else 0xFFC5221F.toInt()
        val tag = if (a.upcoming) "À venir – ${dayHhmm.format(Date(a.start * 1000))}" else a.scope.label
        box.addView(text("⚠ $tag", 12f, color, true))
        box.addView(text(a.alert.header.ifEmpty { "Perturbation" }, 14f, 0xFF222222.toInt(), true))
        if (a.alert.description.isNotEmpty() && a.alert.description != a.alert.header) {
            val d = text(a.alert.description, 13f, 0xFF444444.toInt()).apply { maxLines = 3 }
            var expanded = false
            box.setOnClickListener {
                expanded = !expanded
                d.maxLines = if (expanded) Int.MAX_VALUE else 3
            }
            box.addView(d)
        }
        if (a.alert.url.startsWith("http")) {
            box.addView(text("Plus d'infos", 13f, 0xFF0B5FA5.toInt(), true).apply {
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.alert.url))) }
            })
        }
        return box
    }

    private fun departureRow(d: Departure, nowS: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val mins = ((d.time - nowS) / 60).toInt()
        val whenTxt = if (mins <= 0) "à quai" else "$mins min"
        row.addView(text(whenTxt, 16f, 0xFF0B5FA5.toInt(), true).apply { minWidth = dp(70) })
        row.addView(text(hhmm.format(Date(d.time * 1000)), 13f, 0xFF666666.toInt()).apply { minWidth = dp(50) })
        row.addView(
            text(d.headsign, 13f, 0xFF444444.toInt()).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val delay = d.delaySec
        if (delay != null && abs(delay) >= 60) {
            val m = delay / 60
            row.addView(text(if (m > 0) "+$m min" else "$m min", 12f,
                if (m > 0) 0xFFC5221F.toInt() else 0xFF1E8E3E.toInt(), true))
        }
        return row
    }

    private fun toastText(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    // ---------- Actualisation ----------

    private fun refresh() {
        val favs = Prefs.favorites(this)
        if (favs.isEmpty() || refreshing) return
        refreshing = true
        progress.visibility = View.VISIBLE
        bg.execute {
            var err: String? = null
            val res = try {
                Status.compute(this, favs, withDepartures = true)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                null
            }
            try {
                if (res != null) {
                    // Ce qui est affiché ici n'a pas besoin d'être notifié ensuite.
                    res.allPublishedKeys?.let { Prefs.pruneSeen(this, it) }
                    Prefs.markSeen(this, res.statuses.flatMap { s -> s.alerts.map { it.alert.key } })
                    History.record(this, res)
                }
                Health.recordCheck(this, "appli", res, err)
            } catch (_: Exception) {}
            ui.post {
                refreshing = false
                progress.visibility = View.INVISIBLE
                if (isFinishing) return@post
                if (res != null) {
                    lastStatuses = res.statuses.associateBy { it.fav.key }
                    footer.text = "Mis à jour à ${hhmm.format(Date())} · actualisation auto toutes les 30 s\n" +
                        "Appui long sur une carte pour la retirer.\n" +
                        "Données : TaM / Montpellier Méditerranée Métropole (open data, licence ODbL)."
                } else {
                    footer.text = "Échec de la mise à jour à ${hhmm.format(Date())}. Vérifie ta connexion."
                }
                render()
            }
            // Mise à jour hebdomadaire de la liste des lignes et arrêts, en tâche de fond
            for (n in favs.map { it.network }.toSet()) {
                if (GtfsRepository.isStale(this, n)) {
                    try { GtfsRepository.refresh(this, n) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun updateLineData() {
        val dlg = loading("Mise à jour des lignes et arrêts TaM…")
        bg.execute {
            val errors = ArrayList<String>()
            for (n in Network.values()) {
                try { GtfsRepository.refresh(this, n) } catch (e: Exception) { errors.add("${n.label} : ${e.message}") }
            }
            ui.post {
                dlg.dismiss()
                toastText(if (errors.isEmpty()) "Lignes mises à jour" else "Erreur : " + errors.joinToString("; "))
            }
        }
    }

    private fun loading(msg: String): AlertDialog {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(this@MainActivity))
            addView(text(msg, 15f).apply { setPadding(dp(16), 0, 0, 0) })
        }
        return AlertDialog.Builder(this).setView(box).setCancelable(false).show()
    }

    // ---------- Ajout d'un arrêt : ligne → sens → arrêt ----------

    private fun startAdd() {
        val dlg = loading("Chargement des lignes TaM…\n(la première fois, cela peut prendre une minute)")
        bg.execute {
            val lines = ArrayList<Line>()
            val errors = ArrayList<String>()
            for (n in Network.values()) {
                try {
                    GtfsRepository.ensure(this, n)
                    lines.addAll(GtfsRepository.lines(this, n))
                } catch (e: Exception) {
                    errors.add("${n.label} : ${e.message ?: e.javaClass.simpleName}")
                }
            }
            ui.post {
                dlg.dismiss()
                if (isFinishing) return@post
                if (lines.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Impossible de charger les lignes")
                        .setMessage("Vérifie ta connexion internet puis réessaie.\n\n" + errors.joinToString("\n"))
                        .setPositiveButton("Réessayer") { _, _ -> startAdd() }
                        .setNegativeButton("Annuler", null)
                        .show()
                } else {
                    pickLine(sortLines(lines))
                }
            }
        }
    }

    private fun sortLines(lines: List<Line>): List<Line> {
        fun natural(s: String): String = s.replace(Regex("\\d+")) { it.value.padStart(5, '0') }
        return lines.sortedWith(
            compareBy<Line>({ if (it.isTram) 0 else if (it.network == Network.URBAIN) 1 else 2 }, { natural(it.shortName) })
        )
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.FRANCE)

    /** Liste avec champ de recherche, dans une boîte de dialogue. */
    private fun <T> chooser(title: String, items: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val search = EditText(this).apply {
            hint = "Rechercher…"
            setSingleLine()
        }
        val shown = ArrayList(items)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(items.map(label)))
        val lv = ListView(this).apply { this.adapter = adapter }
        if (items.size > 8) box.addView(search)
        box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Annuler", null).show()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = normalize(s?.toString() ?: "")
                shown.clear()
                shown.addAll(items.filter { q.isEmpty() || normalize(label(it)).contains(q) })
                adapter.clear()
                adapter.addAll(shown.map(label))
                adapter.notifyDataSetChanged()
            }
        })
        lv.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            onPick(shown[pos])
        }
    }

    private fun pickLine(lines: List<Line>) {
        chooser("Choisis ta ligne", lines, { l ->
            val sub = if (l.network == Network.SUBURBAIN) " (suburbain)" else ""
            "${l.label}$sub" + if (l.longName.isNotEmpty()) " – ${l.longName}" else ""
        }) { pickDirection(it) }
    }

    private fun pickDirection(line: Line) {
        if (line.directions.size == 1) {
            pickStop(line, line.directions[0])
            return
        }
        chooser("${line.label} : quel sens ?", line.directions, { "Vers ${it.headsign}" }) { pickStop(line, it) }
    }

    private fun pickStop(line: Line, dir: Direction) {
        chooser("${line.label} → ${dir.headsign} : ton arrêt", dir.stops, { it.name }) { stop ->
            Prefs.addFavorite(
                this,
                Favorite(
                    line.network, line.id, line.shortName, line.isTram, line.color, line.textColor,
                    dir.id, dir.headsign, stop.id, stop.name
                )
            )
            render()
            refresh()
        }
    }
}
