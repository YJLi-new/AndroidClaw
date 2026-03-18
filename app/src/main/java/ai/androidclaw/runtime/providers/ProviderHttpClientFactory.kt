package ai.androidclaw.runtime.providers

import ai.androidclaw.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val PROVIDER_MAX_REQUESTS = 8
private const val PROVIDER_MAX_REQUESTS_PER_HOST = 4
private const val PROVIDER_POOL_IDLE_CONNECTIONS = 4
private const val PROVIDER_POOL_KEEP_ALIVE_MINUTES = 5L
private const val PROVIDER_CONNECT_TIMEOUT_SECONDS = 15L
private const val PROVIDER_WRITE_TIMEOUT_SECONDS = 30L
private const val PROVIDER_READ_TIMEOUT_SECONDS = 60L
private const val PROVIDER_CALL_TIMEOUT_SECONDS = 60L

fun createProviderBaseHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = PROVIDER_MAX_REQUESTS
                maxRequestsPerHost = PROVIDER_MAX_REQUESTS_PER_HOST
            },
        ).connectionPool(
            ConnectionPool(
                PROVIDER_POOL_IDLE_CONNECTIONS,
                PROVIDER_POOL_KEEP_ALIVE_MINUTES,
                TimeUnit.MINUTES,
            ),
        ).retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(PROVIDER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(PROVIDER_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROVIDER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PROVIDER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(providerIdentityInterceptor())
        .build()

private fun providerIdentityInterceptor(): Interceptor =
    Interceptor { chain ->
        val request =
            chain
                .request()
                .newBuilder()
                .header(
                    "User-Agent",
                    "AndroidClaw/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})",
                ).header("X-AndroidClaw-Version", BuildConfig.VERSION_NAME)
                .build()
        chain.proceed(request)
    }
