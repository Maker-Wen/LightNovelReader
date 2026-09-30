package indi.dmzz_yyhyy.lightnovelreader.benchmark.ui

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.concurrent.thread

/** A test-process loopback server whose real PNG pixels are released explicitly by the test. */
class DelayedImageServer : Closeable {
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = 500
    }
    private val requested = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val served = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    private val connections = CopyOnWriteArrayList<Socket>()
    private val workers = CopyOnWriteArrayList<Thread>()
    @Volatile private var closed = false
    private val png = delayedPng()
    val uri: String = "http://127.0.0.1:${listener.localPort}/image-${System.nanoTime()}.png"

    private val acceptThread = thread(name = "reader-image-fixture-accept", isDaemon = true) {
        while (!closed) {
            try {
                val socket = listener.accept()
                connections += socket
                workers += thread(name = "reader-image-fixture-response", isDaemon = true) {
                    respond(socket)
                }
            } catch (_: SocketTimeoutException) {
                // Periodically observe close without leaving an unbounded accept operation.
            } catch (error: Throwable) {
                if (!closed) failure.compareAndSet(null, error)
                break
            }
        }
    }

    fun awaitRequest(timeoutSeconds: Long = 10) {
        check(requested.await(timeoutSeconds, TimeUnit.SECONDS)) {
            "Image request did not reach the loopback fixture: ${failure.get()}"
        }
        checkHealthy()
    }

    fun releaseImage() {
        checkHealthy()
        release.countDown()
    }

    fun awaitServed(timeoutSeconds: Long = 10) {
        check(served.await(timeoutSeconds, TimeUnit.SECONDS)) {
            "Image response did not finish: ${failure.get()}"
        }
        checkHealthy()
    }

    private fun checkHealthy() {
        failure.get()?.let { throw AssertionError("Delayed image fixture failed", it) }
    }

    private fun respond(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                check(reader.readLine()?.startsWith("GET ") == true) { "Expected a PNG GET request" }
                while (!reader.readLine().isNullOrEmpty()) Unit
                requested.countDown()
                val output = socket.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                        "Content-Length: ${png.size}\r\nCache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                // Signature + IHDR + tEXt header. The large valid ancillary chunk permits a
                // heartbeat while the pixels stay gated, avoiding the client's read timeout.
                var sent = 41
                output.write(png, 0, sent)
                output.flush()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                while (!release.await(250, TimeUnit.MILLISECONDS)) {
                    check(!closed && System.nanoTime() < deadline) { "Image release gate timed out" }
                    check(sent < 41 + 4096) { "PNG heartbeat exceeded its ancillary chunk" }
                    output.write(png[sent++].toInt())
                    output.flush()
                }
                if (closed) return
                output.write(png, sent, png.size - sent)
                output.flush()
                served.countDown()
            }
        } catch (error: Throwable) {
            if (!closed) failure.compareAndSet(null, error)
        } finally {
            connections -= socket
        }
    }

    override fun close() {
        closed = true
        release.countDown()
        listener.close()
        connections.forEach { socket ->
            try { socket.close() } catch (_: SocketException) { }
        }
        acceptThread.join(1_000)
        workers.forEach { it.join(500) }
    }

    private fun delayedPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
        val original = ByteArrayOutputStream().use { bytes ->
            try {
                bitmap.eraseColor(Color.rgb(40, 150, 210))
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        // A PNG starts with an eight-byte signature and a 25-byte IHDR chunk. Insert a legal
        // tEXt chunk before IDAT; its CRC covers the chunk type and data, as required by PNG.
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val padding = ByteArray(4096) { ' '.code.toByte() }.apply {
            val keyword = "fixture\u0000".toByteArray(Charsets.US_ASCII)
            keyword.copyInto(this)
        }
        val crc = CRC32().apply { update(type); update(padding) }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(original, 0, 33)
                output.writeInt(padding.size)
                output.write(type)
                output.write(padding)
                output.writeInt(crc.value.toInt())
                output.write(original, 33, original.size - 33)
            }
            bytes.toByteArray()
        }
    }
}
