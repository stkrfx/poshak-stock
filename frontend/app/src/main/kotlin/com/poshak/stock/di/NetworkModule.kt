package com.poshak.stock.di

import com.poshak.stock.BuildConfig
import com.poshak.stock.data.remote.api.AuthApi
import com.poshak.stock.data.remote.api.StockApi
import com.poshak.stock.data.remote.api.SyncApi
import com.poshak.stock.data.remote.api.UpdateApi
import com.poshak.stock.data.remote.interceptor.AuthHeaderInterceptor
import com.poshak.stock.data.remote.interceptor.TokenRefreshAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt module providing [Json], [OkHttpClient], [Retrofit], and all API service interfaces
 * ([AuthApi], [StockApi], [SyncApi], [UpdateApi]).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val JSON_MEDIA_TYPE = "application/json; charset=UTF-8"

    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
            isLenient = true
        }
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    /**
     * Dedicated OkHttpClient for /v1/auth/ endpoints without [TokenRefreshAuthenticator]
     * to prevent cyclic dependency during token refresh.
     */
    @Provides
    @Singleton
    @Named("AuthOkHttpClient")
    fun provideAuthOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(
        @Named("AuthOkHttpClient") authOkHttpClient: OkHttpClient,
        json: Json
    ): AuthApi {
        val baseUrl = ensureTrailingSlash(BuildConfig.API_BASE_URL)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authOkHttpClient)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
            .create(AuthApi::class.java)
    }

    /**
     * Primary authenticated OkHttpClient with [AuthHeaderInterceptor] and [TokenRefreshAuthenticator].
     */
    @Provides
    @Singleton
    fun provideAuthenticatedOkHttpClient(
        authHeaderInterceptor: AuthHeaderInterceptor,
        tokenRefreshAuthenticator: TokenRefreshAuthenticator,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authHeaderInterceptor)
            .authenticator(tokenRefreshAuthenticator)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        val baseUrl = ensureTrailingSlash(BuildConfig.API_BASE_URL)
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideStockApi(retrofit: Retrofit): StockApi {
        return retrofit.create(StockApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi {
        return retrofit.create(SyncApi::class.java)
    }

    @Provides
    @Singleton
    fun provideUpdateApi(retrofit: Retrofit): UpdateApi {
        return retrofit.create(UpdateApi::class.java)
    }

    private fun ensureTrailingSlash(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
