package com.sbcfg.manager.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.sbcfg.manager.BuildConfig
import com.sbcfg.manager.domain.model.VpnServer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
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

    @GET("api/meta/{token}")
    suspend fun getMeta(
        @Path("token") token: String,
    ): Response<ServersResponseDto>
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

    private val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    fun createServiceForUrl(baseUrl: String): ConfigApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            // Scalars для Response<String> (getConfig), kotlinx-serialization
            // для DTO (getMeta). Retrofit выбирает фабрику по типу.
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
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

    /**
     * Получить список VPN-серверов через GET /api/meta/{token}. Принимает
     * тот же configUrl, что и [fetchConfig] — base/token извлекаются из него,
     * чтобы клиенту не нужно было хранить два URL'а.
     */
    suspend fun fetchMeta(configUrl: String): Result<List<VpnServer>> {
        return try {
            val url = URL(configUrl)
            val baseUrl = "${url.protocol}://${url.host}${if (url.port != -1) ":${url.port}" else ""}/"
            val pathParts = url.path.trimStart('/').split("/")

            val tokenIndex = pathParts.indexOf("config") + 1
            if (tokenIndex == 0 || tokenIndex >= pathParts.size) {
                return Result.failure(IllegalArgumentException("Неверный формат ссылки"))
            }
            val token = pathParts[tokenIndex]

            val service = createServiceForUrl(baseUrl)
            val response = service.getMeta(token)

            when {
                response.isSuccessful -> {
                    val body = response.body()
                        ?: return Result.failure(IllegalStateException("Получен пустой ответ"))
                    val servers = body.servers.map {
                        VpnServer(
                            tag = it.tag,
                            displayName = it.displayName,
                            countryCode = it.countryCode,
                            speedtestUrl = it.speedtestUrl,
                        )
                    }
                    Result.success(servers)
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
