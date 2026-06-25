package com.example.pisignage

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClient {
    val instance: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
