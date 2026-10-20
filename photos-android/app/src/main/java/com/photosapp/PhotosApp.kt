package com.photosapp

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.photosapp.di.ServiceLocator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PhotosApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        CrashDiagnostics.install(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .okHttpClient {
                okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                    val original = chain.request()
                    val email = original.header("X-Photos-Account")
                    val builder = original.newBuilder().removeHeader("X-Photos-Account")
                    val host = original.url.host
                    if (email != null && (host == "www.googleapis.com" || host.endsWith(".googleusercontent.com"))) {
                        val token = try { ServiceLocator.tokenForAccount(email) } catch (e: Exception) {
                            throw java.io.IOException("Reconnect account to view this photo", e)
                        }
                        builder.header("Authorization", "Bearer $token")
                    }
                    val response = chain.proceed(builder.build())
                    if (email != null && host.endsWith(".googleusercontent.com") && response.code in listOf(401, 403, 404)) {
                        // Thumbnail links expire even when media itself has no entry in the change feed.
                        val cached = ServiceLocator.db.cloudMediaDao().forThumbnail(email, original.url.toString())
                        val renewed = cached?.let { item -> runCatching {
                            kotlinx.coroutines.runBlocking { ServiceLocator.getApiForAccount(email).getFile(item.fileId).thumbnailLink }
                        }.getOrNull() }?.toHttpUrlOrNull()
                        if (cached != null && renewed != null && renewed.isHttps && renewed.host.endsWith(".googleusercontent.com") && renewed != original.url) {
                            ServiceLocator.db.cloudMediaDao().updateThumbnail(email, cached.fileId, renewed.toString())
                            response.close()
                            return@addInterceptor chain.proceed(builder.url(renewed).header("Authorization", "Bearer "+ServiceLocator.tokenForAccount(email)).build())
                        }
                    }
                    response
                }.build()
            }
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .build()
    }
}
