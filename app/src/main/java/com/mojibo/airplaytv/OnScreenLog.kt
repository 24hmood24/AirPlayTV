package com.mojibo.airplaytv

import android.os.Handler
import android.os.Looper

object OnScreenLog {
    private val builder = StringBuilder()
    private var listener: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
        val current = builder.toString()
        if (current.isNotEmpty()) l?.invoke(current)
    }

    fun log(line: String) {
        synchronized(builder) {
            builder.append(line).append("\n")
            if (builder.length > 4000) builder.delete(0, builder.length - 4000)
        }
        val snapshot = builder.toString()
        mainHandler.post { listener?.invoke(snapshot) }
    }
}