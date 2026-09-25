package com.novelscraper.app.net

import com.novelscraper.app.data.AuthConfig
import com.novelscraper.app.data.LoginRequest
import com.novelscraper.app.data.RegisterRequest
import com.novelscraper.app.data.SyncRequest
import com.novelscraper.app.data.SyncResponse
import com.novelscraper.app.data.UserRead
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Typed view of the NovelScraper REST API (backend app/api routers).
 *
 * An account does one thing: it syncs the library between a user's devices. The
 * novels themselves are scraped, stored, imported and read on the device, so
 * there is nothing else here.
 */
interface Api {
    @GET("api/auth/config")
    suspend fun authConfig(): AuthConfig

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): UserRead

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): UserRead

    @POST("api/auth/logout")
    suspend fun logout()

    @GET("api/auth/me")
    suspend fun me(): UserRead

    /** Library sync: send this device's changes, get the other devices'. */
    @POST("api/sync")
    suspend fun sync(@Body body: SyncRequest): SyncResponse
}
