package com.exilon.tides.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the TideCheck API key (from BuildConfig) to every request as `X-API-Key`. */
class ApiKeyInterceptor(private val apiKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-API-Key", apiKey)
            .build()
        return chain.proceed(request)
    }
}
