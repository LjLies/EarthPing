package io.github.luccoj.alertroid.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tracks whether any of our activities is on screen (lets the service start the alert UI). */
object AppVisibility {
    @Volatile var visible = false
        private set
    private var started = 0

    fun register(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; visible = started > 0 }
            override fun onActivityStopped(a: Activity) { started--; visible = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}

/** Minimal helpers to build simple screens in code. */
object Ui {
    fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun scrollColumn(a: Activity): LinearLayout {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(a, 16)
            setPadding(p, p, p, p)
        }
        val sv = ScrollView(a).apply { addView(col); isFillViewport = true }
        a.setContentView(sv)
        // Keep content clear of system bars on edge-to-edge (Android 15+).
        sv.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        return col
    }

    fun card(parent: LinearLayout, tint: Int? = null): LinearLayout {
        val ctx = parent.context
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 14)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(tint ?: if (isNight(ctx)) 0xFF262A30.toInt() else 0xFFF1F3F6.toInt())
            }
        }
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(ctx, 12)
        })
        return c
    }

    fun title(parent: ViewGroup, text: CharSequence, size: Float = 18f): TextView = TextView(parent.context).apply {
        this.text = text
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(context, 6))
        parent.addView(this)
    }

    fun text(parent: ViewGroup, text: CharSequence = "", size: Float = 14f, color: Int? = null): TextView = TextView(parent.context).apply {
        this.text = text
        textSize = size
        color?.let { setTextColor(it) }
        setPadding(0, dp(context, 2), 0, dp(context, 2))
        parent.addView(this)
    }

    fun button(parent: ViewGroup, text: CharSequence, onClick: (View) -> Unit): Button = Button(parent.context).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener(onClick)
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun row(parent: ViewGroup): LinearLayout = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun field(parent: ViewGroup, label: String, value: String, numeric: Boolean = true, signed: Boolean = false, decimal: Boolean = false): EditText {
        text(parent, label, 13f)
        return EditText(parent.context).apply {
            setText(value)
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0) or
                    (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            } else InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    const val OK = 0xFF2E7D32.toInt()
    const val WARN = 0xFFE65100.toInt()
    const val BAD = 0xFFC62828.toInt()
    val TRANSPARENT = Color.TRANSPARENT
}
