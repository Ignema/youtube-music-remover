package com.musicremover.app.processing

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import com.musicremover.app.data.ApiClient
import com.musicremover.app.data.ProcessRequest
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException

/** Runs jobs on a Murem HTTP server (PC, Docker or Termux on this device). */
class RemoteServerBackend(
    context: Context,
    val baseUrl: String,
    private val cache: ResultCache,
) : ProcessingBackend {
    companion object {
        val SERVER_MODELS = listOf(
            "Kim_Vocal_2.onnx",
            "UVR-MDX-NET-Inst_HQ_3.onnx",
            "UVR_MDXNET_KARA_2.onnx",
            "vocals_mel_band_roformer.ckpt",
            "model_bs_roformer_ep_317_sdr_12.9755.ckpt",
        )
    }

    private val resolver = context.applicationContext.contentResolver

    private fun api() = ApiClient.getService(baseUrl)

    override val id = "server"

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL("$baseUrl/health").openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.getInputStream().bufferedReader().readText().contains("ok")
        } catch (_: Exception) { false }
    }

    override suspend fun info(url: String): VideoInfo = api().info(url)

    override fun supportedModels(): List<String> = SERVER_MODELS

    override suspend fun startUrl(url: String, options: ProcessOptions): String =
        api().process(
            ProcessRequest(
                url = url,
                model = options.model,
                audio_only = options.audioOnly,
                bitrate = options.bitrate,
            )
        ).job_id

    override suspend fun startFile(uri: Uri, fileName: String, options: ProcessOptions): String {
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.close() ?: throw Exception("Cannot read file")
        }
        val text = "text/plain".toMediaTypeOrNull()
        val filePart = MultipartBody.Part.createFormData(
            "file", fileName,
            ContentUriRequestBody(resolver, uri, "video/*".toMediaTypeOrNull()),
        )
        return api().upload(
            filePart,
            options.model.toRequestBody(text),
            options.batchSize.toString().toRequestBody(text),
            options.audioOnly.toString().toRequestBody(text),
            options.bitrate.toRequestBody(text),
        ).job_id
    }

    override fun events(jobId: String): Flow<JobEvent> = flow {
        var retries = 0
        while (true) {
            delay(2500)
            val status = try {
                api().status(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                retries++
                if (retries >= 30) {
                    emit(JobEvent.ConnectionLost)
                    return@flow
                }
                delay(retries * 1000L)
                continue
            }
            retries = 0
            emit(JobEvent.Progress(status.status, status.progress, status.error, status.filename, status.metadata))
            when (status.status) {
                "done" -> {
                    val name = status.filename ?: "Done"
                    emit(JobEvent.Done(fetchResult(jobId, name), name, status.metadata))
                    return@flow
                }
                "error", "cancelled" -> {
                    emit(JobEvent.Failed(status.status, status.error))
                    return@flow
                }
            }
        }
    }

    override suspend fun cancel(jobId: String) {
        try { api().cancel(jobId) } catch (_: Exception) {}
    }

    override suspend fun isResultAvailable(jobId: String): Boolean =
        api().status(jobId).status == "done"

    override suspend fun fetchResult(jobId: String, filename: String): File? =
        // A finished job must not lose its result to a late cancel
        withContext(Dispatchers.IO + NonCancellable) {
            cache.cacheFromUrl(remoteResultUrl(jobId), jobId, filename)
        }

    override suspend fun waveform(jobId: String): List<Float>? = try {
        api().waveform(jobId).waveform.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }

    override fun remoteResultUrl(jobId: String): String = "$baseUrl/api/download/$jobId"

    /** Streams a content:// Uri into the request instead of loading it into memory. */
    private class ContentUriRequestBody(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val type: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = try {
            val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (length == AssetFileDescriptor.UNKNOWN_LENGTH || length < 0) -1L else length
        } catch (_: Exception) { -1L }

        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read file")
            input.source().use { sink.writeAll(it) }
        }
    }
}
