package com.mojibo.airplaytv.player

/**
 * نقطة تزامن مشتركة بين طابور الفيديو وطابور الصوت.
 * أي طابور يصحح نفسه (فائض/تعثر شبكة)، يحدّث هذا الوقت،
 * والطرف الثاني يراقبه ويتزامن معه بنفس اللحظة.
 */
object SyncClock {
    @Volatile var lastResyncAtMs: Long = 0L
}