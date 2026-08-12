package com.novelscraper.app.net

import com.novelscraper.app.data.AuthConfig
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.LoginRequest
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.data.UserRead
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Typed view of the NovelScraper REST API (backend app/api routers). */
interface Api {
    @GET("api/auth/config")
    suspend fun authConfig(): AuthConfig

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): UserRead

    @POST("api/auth/logout")
    suspend fun logout()

    @GET("api/auth/me")
    suspend fun me(): UserRead

    @GET("api/books")
    suspend fun books(): List<BookRead>

    @GET("api/books/{id}")
    suspend fun book(@Path("id") id: Int): BookRead

    @GET("api/books/{id}/chapters")
    suspend fun chapters(@Path("id") id: Int): List<ChapterListItem>

    @GET("api/books/{id}/chapters/{pos}")
    suspend fun chapter(@Path("id") id: Int, @Path("pos") pos: Int): ChapterRead

    @GET("api/books/{id}/progress")
    suspend fun progress(@Path("id") id: Int): ReadingProgressRead

    @PUT("api/books/{id}/progress")
    suspend fun putProgress(@Path("id") id: Int, @Body body: ProgressUpdate): ReadingProgressRead
}
