package fr.perso.tamligne

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Petits utilitaires d'interface partagés par les écrans. */
object Ui {
    const val BLUE = 0xFF0B5FA5.toInt()
    const val GREEN = 0xFF1E8E3E.toInt()
    const val ORANGE = 0xFFB26A00.toInt()
    const val RED = 0xFFC5221F.toInt()
    const val GREY = 0xFF777777.toInt()
    const val DARK = 0xFF222222.toInt()

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 15f, color: Int = DARK, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun card(ctx: Context, bg: Int = Color.WHITE): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(ctx, 12).toFloat()
        }
        elevation = dp(ctx, 2).toFloat()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(ctx, 6), 0, dp(ctx, 6)) }
    }

    /** Écran défilant simple ; renvoie le conteneur où ajouter le contenu. */
    fun scrollScreen(a: Activity): LinearLayout {
        val scroll = ScrollView(a).apply { setBackgroundColor(0xFFF2F4F7.toInt()) }
        val inner = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 24))
        }
        scroll.addView(inner)
        a.setContentView(scroll)
        return inner
    }

    fun levelColor(l: Health.Level) = when (l) {
        Health.Level.OK -> GREEN
        Health.Level.WARN -> ORANGE
        Health.Level.BAD -> RED
        Health.Level.NEUTRAL -> GREY
    }

    fun levelBg(l: Health.Level) = when (l) {
        Health.Level.OK -> 0xFFE6F4EA.toInt()
        Health.Level.WARN -> 0xFFFFF4E0.toInt()
        Health.Level.BAD -> 0xFFFDECEA.toInt()
        Health.Level.NEUTRAL -> 0xFFECEFF3.toInt()
    }

    fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
    }
}
