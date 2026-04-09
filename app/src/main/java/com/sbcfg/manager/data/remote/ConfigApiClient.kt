package com.sbcfg.manager.data.remote

import com.sbcfg.manager.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface ConfigApiService {
    @GET("api/config/{token}")
    suspend fun getConfig(
        @Path("token") token: String,
        @Query("platform") platform: String = "android"
    ): Response<String>
}

@Singleton
class ConfigApiClient @Inject constructor() {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        }
                    )
                }
            }
            .build()
    }

    fun createServiceForUrl(baseUrl: String): ConfigApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(ConfigApiService::class.java)
    }

    suspend fun fetchConfig(configUrl: String): Result<String> {
        return try {
            val url = URL(configUrl)
            val baseUrl = "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}/"
            val pathParts = url.path.trimStart('/').split("/")

            // Expected format: api/config/{token}
            val tokenIndex = pathParts.indexOf("config") + 1
            if (tokenIndex == 0 || tokenIndex >= pathParts.size) {
                return Result.failure(IllegalArgumentException("Неверный формат ссылки"))
            }
            val token = pathParts[tokenIndex]

            val service = createServiceForUrl(baseUrl)
            val response = service.getConfig(token)

            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body.isNullOrBlank()) {
                        Result.failure(IllegalStateException("Получен пустой ответ"))
                    } else {
                        Result.success(body)
                    }
                }
                response.code() == 404 -> Result.failure(
                    IllegalArgumentException("Неверная ссылка конфигурации")
                )
                response.code() == 429 -> {
                    val retryAfter = response.headers()["Retry-After"] ?: "60"
                    Result.failure(
                        IllegalStateException("Слишком много запросов. Подождите $retryAfter сек.")
                    )
                }
                response.code() in 500..599 -> Result.failure(
                    IllegalStateException("Ошибка сервера. Попробуйте позже.")
                )
                else -> Result.failure(
                    IllegalStateException("Ошибка: ${response.code()}")
                )
            }
        } catch (e: IOException) {
            Result.failure(IOException("Нет соединения с сервером"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
