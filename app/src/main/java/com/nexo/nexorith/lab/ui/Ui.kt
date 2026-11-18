package com.nexo.nexorith.lab.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Palette {
    val BG = Color.parseColor("#0B0B10")
    val PANEL = Color.parseColor("#15151D")
    val ACCENT = Color.parseColor("#5B63D8")
    val TEXT = Color.parseColor("#E8E8F0")
    val MUTED = Color.parseColor("#9A9AB0")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun Context.actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(Palette.TEXT)
    background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Palette.ACCENT)
    }
    setPadding(dp(12), 0, dp(12), 0)
    minHeight = 0
    minimumHeight = dp(40)
    setOnClickListener { onClick() }
}
