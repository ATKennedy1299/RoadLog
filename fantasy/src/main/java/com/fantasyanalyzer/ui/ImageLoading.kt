package com.fantasyanalyzer.ui

import android.content.Context
import android.webkit.CookieManager
import coil.ImageLoader
import coil.decode.SvgDecoder
import okhttp3.OkHttpClient

/**
 * The app's image loader: decodes SVG (ESPN's team logo packs are SVGs) and
 * sends the ESPN sign-in with requests to ESPN's own image hosts, since logos
 * a manager uploaded are served from ESPN behind the login.
 */
fun buildImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .components { add(SvgDecoder.Factory()) }
    .okHttpClient {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val cookie = if (host == "espn.com" || host.endsWith(".espn.com")) {
                    try {
                        CookieManager.getInstance().getCookie(request.url.toString())
                    } catch (e: Exception) {
                        null // no WebView available
                    }
                } else null
                chain.proceed(if (cookie.isNullOrBlank()) request else request.newBuilder().header("Cookie", cookie).build())
            }
            .build()
    }
    .crossfade(true)
    .build()
