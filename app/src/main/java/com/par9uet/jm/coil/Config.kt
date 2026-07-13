package com.par9uet.jm.coil

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import com.par9uet.jm.cache.getCommonCacheDir
import okhttp3.Interceptor
import okhttp3.OkHttpClient

private val cdnHeaderInterceptor = Interceptor { chain ->
    val request = chain.request().newBuilder()
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36")
        .header("Referer", "https://18comic.vip")
        .build()
    chain.proceed(request)
}

fun createAsyncImageLoader(context: Context): ImageLoader {
    return ImageLoader.Builder(context)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor(cdnHeaderInterceptor)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(getCommonCacheDir(context)) // 自定义目录
                .maxSizeBytes(1024L * 1024 * 1024) // 200MB
                .build()
        }
        .build()
}