package com.photosapp.di

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.photosapp.BuildConfig
import com.photosapp.data.api.ApiService
import com.photosapp.data.local.PrefsRepository
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

object ServiceLocator {
    private lateinit var appContext: Context
    val context: Context get() = appContext
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun init(context: Context) { appContext = context.applicationContext }
    val prefs: PrefsRepository by lazy { PrefsRepository(appContext) }
    val db: com.photosapp.data.local.db.AppDatabase by lazy {
        com.photosapp.data.local.db.AppDatabase.getDatabase(appContext)
    }

    private val apis = java.util.concurrent.ConcurrentHashMap<String, ApiService>()
    private data class CachedToken(val value: String, val obtainedAt: Long)
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, CachedToken>()
    private val tokenLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val idPools = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<String>>()
    private val idLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    fun getApiForAccount(email: String): ApiService = apis.getOrPut(email) { createApi(email) }

    /** Blocking Google account token lookup; call from an IO dispatcher or OkHttp interceptor. */
    fun tokenForAccount(email: String): String = synchronized(tokenLocks.getOrPut(email) { Any() }) {
        val now = android.os.SystemClock.elapsedRealtime()
        tokens[email]?.takeIf { now - it.obtainedAt < 5 * 60 * 1000L }?.value ?: GoogleAuthUtil.getToken(
            appContext, Account(email, "com.google"), "oauth2:$DRIVE_SCOPE"
        ).also { tokens[email] = CachedToken(it, now) }
    }
    private fun renewToken(email: String, rejected: String): String = synchronized(tokenLocks.getOrPut(email) { Any() }) {
        tokens[email]?.takeIf { it.value != rejected }?.value ?: run {
            tokens.remove(email)
            GoogleAuthUtil.clearToken(appContext, rejected)
            tokenForAccount(email)
        }
    }
    suspend fun reserveDriveFileId(email: String): String = idLocks.getOrPut(email) { kotlinx.coroutines.sync.Mutex() }.withLock {
        val pool = idPools.getOrPut(email) { java.util.ArrayDeque() }
        if (pool.isEmpty()) pool.addAll(getApiForAccount(email).generateFileId().ids)
        check(pool.isNotEmpty()) { "Missing Drive file IDs" }
        pool.removeFirst()
    }
    fun forgetAccount(email: String) { tokens.remove(email); apis.remove(email); idPools.remove(email) }

    private fun createApi(email: String): ApiService {
        val auth = Interceptor { chain ->
            check(chain.request().url.host == "www.googleapis.com") { "Unexpected Drive API host" }
            val token = try { tokenForAccount(email) } catch (e: Exception) {
                throw java.io.IOException("Reconnect Google account: $email", e)
            }
            val request = chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            val response = chain.proceed(request)
            if (response.code != 401) return@Interceptor response
            val renewed = try {
                renewToken(email, token)
            } catch (e: Exception) { return@Interceptor response }
            response.close()
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $renewed").build())
        }
        val logging = HttpLoggingInterceptor { line ->
            // Upload session URLs contain a private upload_id. Never write them into logcat.
            android.util.Log.d("DriveHttp", line.replace(Regex("https://[^\\s]+"), "<Drive endpoint>"))
        }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder().addInterceptor(auth).addInterceptor(logging)
            // Drive uses 308 for Resume Incomplete; it must reach the uploader, not redirect replay.
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
        return Retrofit.Builder().baseUrl("https://www.googleapis.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
    }
}
