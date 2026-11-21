package rs.battery.lab

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException

internal interface BatteryReader {
    fun initialize()
    fun read(): BmsReading
}

internal interface ElmLink {
    fun command(value: String, timeoutMs: Long = 1500): String
}

internal class ElmReplyTimeout : IOException("OBD odgovor nije stigao")

/** Adapter transport only; no vehicle commands are inferred or scanned. */
internal class BluetoothElmLink(socket: BluetoothSocket) : ElmLink {
    private val input = socket.inputStream
    private val output = socket.outputStream
    override fun command(value: String, timeoutMs: Long): String {
        while (input.available() > 0) input.read()
        output.write("$value\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val reply = StringBuilder()
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted) throw IOException("OBD prekinut")
            if (input.available() > 0) {
                val c = input.read()
                if (c == -1) throw IOException("OBD veza je zatvorena")
                if (c == '>'.code) return reply.toString()
                if (reply.length >= 8192) throw IOException("Predugačak OBD odgovor")
                reply.append(c.toChar())
            } else Thread.sleep(10)
        }
        throw ElmReplyTimeout()
    }
}

/** CAF1/H0 replies. Reject incomplete, reordered or wrong-service responses. */
internal fun zoePayload(raw: String, prefix: String, count: Int): List<Int>? {
    val lines = raw.uppercase().split('\r', '\n').map { it.filterNot(Char::isWhitespace) }.filter(String::isNotEmpty)
    fun decode(hex: String): List<Int>? {
        if (!hex.startsWith(prefix) || hex.length < prefix.length + count * 2 || hex.length % 2 != 0 ||
            !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return hex.drop(prefix.length).take(count * 2).chunked(2).map { it.toInt(16) }
    }
    for ((i, line) in lines.withIndex()) {
        if (line.length in 1..3 && line.all { it in '0'..'9' || it in 'A'..'F' } &&
            lines.getOrNull(i + 1)?.startsWith("0:") == true) {
            val size = line.toInt(16)
            val data = StringBuilder()
            var frame = 0
            var valid = size in (prefix.length / 2 + count)..2048
            for (part in lines.drop(i + 1).takeWhile { it.length >= 2 && it[1] == ':' }) {
                if (part[0].digitToIntOrNull(16) != frame % 16 || part.drop(2).length % 2 != 0) valid = false
                data.append(part.drop(2)); frame++
            }
            if (valid && data.length >= size * 2) decode(data.take(size * 2).toString())?.let { return it }
        }
        if (line.contains(':')) continue
        decode(line)?.let { return it }
        // Some adapters retain the single-frame payload length even with ATH0.
        val singleLength = line.take(2).toIntOrNull(16)
        if (singleLength != null && singleLength in 1..7 && singleLength >= prefix.length / 2 + count &&
            line.length >= 2 + singleLength * 2)
            decode(line.substring(2, 2 + singleLength * 2))?.let { return it }
    }
    return null
}

internal fun List<Int>.unsignedAt(offset: Int, bytes: Int): Long? {
    if (offset < 0 || bytes !in 1..4 || offset + bytes > size) return null
    return subList(offset, offset + bytes).fold(0L) { acc, byte -> (acc shl 8) or byte.toLong() }
}
