package com.mojibo.airplaytv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CrashDisplayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getStringExtra("crash_text") ?: "لا يوجد تفاصيل"

        val textView = TextView(this).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(textView)
        }

        setContentView(scrollView)
    }
}