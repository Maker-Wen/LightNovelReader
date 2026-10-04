package indi.dmzz_yyhyy.lightnovelreader.data.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpIndependentUpdateTransport : IndependentUpdateTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    override suspend fun get(url: String): IndependentUpdateHttpResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LightNovelReader-MakerWen")
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) IndependentUpdateHttpResponse(it.code, "")
                        else {
                            val bytes = it.body.byteStream().use { body ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var count = body.read(buffer)
                                while (count != -1) {
                                    if (output.size() + count > IndependentGitHubUpdateSource.MAX_RESPONSE_BYTES) {
                                        throw IOException("Update response is too large")
                                    }
                                    output.write(buffer, 0, count)
                                    count = body.read(buffer)
                                }
                                output.toByteArray()
                            }
                            IndependentUpdateHttpResponse(it.code, bytes.toString(Charsets.UTF_8))
                        }
                    }
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}
