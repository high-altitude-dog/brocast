package org.beachsync.proto

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.LinkedBlockingQueue

/** A CSV file written on its own thread, so no caller ever waits for storage. */
class CsvLog(file: File, header: String) {
    private val queue = LinkedBlockingQueue<String>()
    private val out = BufferedWriter(FileWriter(file))
    private val writer = Thread({
        try {
            while (true) {
                val line = queue.take()
                if (line == STOP) break
                out.write(line); out.newLine()
                if (queue.isEmpty()) out.flush()
            }
        } catch (_: InterruptedException) {
        } finally {
            out.close()
        }
    }, "csv-${file.name}")

    init {
        out.write(header); out.newLine()
        writer.start()
    }

    fun row(vararg v: Any?) {
        queue.offer(v.joinToString(",") { x ->
            when (x) {
                null -> ""
                is Double -> if (x.isNaN()) "" else x.toString()
                else -> x.toString()
            }
        })
    }

    fun close() { queue.offer(STOP) }

    private companion object { const val STOP = "\u0000STOP" }
}
