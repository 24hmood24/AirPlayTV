package com.mojibo.airplaytv.rtsp

import java.io.BufferedInputStream

/**
 * AirPlay control is just RTSP: a text protocol almost identical to HTTP/1.1
 * (method, path, headers, optional binary body whose length is given by Content-Length).
 */
data class RtspRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

class RtspResponseBuilder(private val cseq: String) {
    private val headers = LinkedHashMap<String, String>()
    private var body: ByteArray = ByteArray(0)
    private var status = "200 OK"

    fun header(key: String, value: String) = apply { headers[key] = value }
    fun body(bytes: ByteArray, contentType: String) = apply {
        body = bytes
        headers["Content-Type"] = contentType
        headers["Content-Length"] = bytes.size.toString()
    }
    fun status(s: String) = apply { status = s }

    fun build(): ByteArray {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $status\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("Server: MojiboAirplayTV/0.1\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        val headerBytes = sb.toString().toByteArray(Charsets.US_ASCII)
        return headerBytes + body
    }
}

/** Reads one RTSP request off a raw socket input stream. Returns null on clean EOF. */
fun readRtspRequest(input: BufferedInputStream): RtspRequest? {
    val requestLine = readLine(input) ?: return null
    if (requestLine.isBlank()) return null
    val parts = requestLine.split(" ")
    if (parts.size < 2) return null
    val method = parts[0]
    val path = parts[1]

    val headers = LinkedHashMap<String, String>()
    while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
            headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
    }

    val len = headers["Content-Length"]?.toIntOrNull() ?: 0
    val body = ByteArray(len)
    var read = 0
    while (read < len) {
        val n = input.read(body, read, len - read)
        if (n < 0) break
        read += n
    }
    return RtspRequest(method, path, headers, body)
}

private fun readLine(input: BufferedInputStream): String? {
    val sb = StringBuilder()
    var prevCr = false
    while (true) {
        val b = input.read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        val c = b.toChar()
        if (c == '\n') {
            if (prevCr) sb.setLength(sb.length) // no-op, cr already excluded
            return sb.toString()
        }
        if (c == '\r') {
            prevCr = true
            continue
        }
        sb.append(c)
    }
}
