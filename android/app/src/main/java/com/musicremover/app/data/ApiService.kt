package com.musicremover.app.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

data class ProcessRequest(
    val url: String,
    val model: String = "Kim_Vocal_2.onnx",
    val batch_size: Int = 4,
    val audio_only: Boolean = false,
    val bitrate: String = "192k",
)

data class ProcessResponse(val job_id: String)

data class StatusResponse(
    val status: String,
    val progress: Int = 0,
    val error: String? = null,
    val filename: String? = null,
    val metadata: VideoInfo? = null,
)

data class ModelsResponse(val models: List<String>)

data class VideoInfo(
    val title: String = "",
    val channel: String = "",
    val duration: Int = 0,
    val thumbnail: String = "",
    val view_count: Long = 0,
    val upload_date: String = "",
    val description: String = "",
)

interface ApiService {
    @POST("/api/process")
    suspend fun process(@Body req: ProcessRequest): ProcessResponse

    @Multipart
    @POST("/api/upload")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("batch_size") batchSize: RequestBody,
        @Part("audio_only") audioOnly: RequestBody,
        @Part("bitrate") bitrate: RequestBody,
    ): ProcessResponse

    @GET("/api/status/{jobId}")
    suspend fun status(@Path("jobId") jobId: String): StatusResponse

    @POST("/api/cancel/{jobId}")
    suspend fun cancel(@Path("jobId") jobId: String): StatusResponse

    @GET("/api/info")
    suspend fun info(@retrofit2.http.Query("url") url: String): VideoInfo

    @GET("/api/waveform/{jobId}")
    suspend fun waveform(@Path("jobId") jobId: String): WaveformResponse

    @GET("/api/models")
    suspend fun models(): ModelsResponse
}

data class WaveformResponse(val waveform: List<Float> = emptyList())
