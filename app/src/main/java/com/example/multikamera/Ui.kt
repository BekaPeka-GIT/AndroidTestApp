package com.example.multikamera

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Drobne pomocnicze funkcje do budowania widoków w kodzie. */
fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun Context.label(text: String = "", sizeSp: Float = 15f): TextView = TextView(this).apply {
    this.text = text
    setTextColor(Color.WHITE)
    textSize = sizeSp
}

fun Context.pillButton(text: String, color: Int = Color.rgb(60, 60, 70), onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(color)
        }
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

fun Context.row(vararg children: android.view.View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    children.forEach {
        addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }
}

val RED = Color.rgb(210, 40, 40)
