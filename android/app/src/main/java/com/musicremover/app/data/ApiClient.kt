package com.musicremover.app.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private var currentBaseUrl: String = ""
    private var currentService: ApiService? = null

    fun getService(baseUrl: String): ApiService {
        if (baseUrl != currentBaseUrl || currentService == null) {
            val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            currentService = Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
            currentBaseUrl = baseUrl
        }
        return currentService!!
    }
}
