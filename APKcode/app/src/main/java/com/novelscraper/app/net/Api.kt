package com.novelscraper.app.net

import com.novelscraper.app.data.AuthConfig
import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookRead
import com.novelscraper.app.data.BookReorder
import com.novelscraper.app.data.ChapterListItem
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.CollectionCreate
import com.novelscraper.app.data.CollectionRead
import com.novelscraper.app.data.CollectionUpdate
import com.novelscraper.app.data.JobCreate
import com.novelscraper.app.data.JobRead
import com.novelscraper.app.data.StatsRead
import com.novelscraper.app.data.LoginRequest
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.data.ReadingProgressRead
import com.novelscraper.app.data.RegisterRequest
import com.novelscraper.app.data.UserRead
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

/** Typed view of the NovelScraper REST API (backend app/api routers). */
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

    @GET("api/books")
    suspend fun books(): List<BookRead>

    @GET("api/collections")
    suspend fun collections(): List<CollectionRead>

    @POST("api/collections")
    suspend fun createCollection(@Body body: CollectionCreate): CollectionRead

    @PATCH("api/collections/{id}")
    suspend fun updateCollection(@Path("id") id: Int, @Body body: CollectionUpdate): CollectionRead

    @DELETE("api/collections/{id}")
    suspend fun deleteCollection(@Path("id") id: Int)

    @POST("api/books/reorder")
    suspend fun reorderBooks(@Body body: BookReorder)

    @PUT("api/books/{id}/collections")
    suspend fun setBookCollections(@Path("id") id: Int, @Body body: BookCollectionsUpdate): BookRead

    // Scraping jobs
    @POST("api/jobs")
    suspend fun createJob(@Body body: JobCreate): JobRead

    @GET("api/jobs")
    suspend fun jobs(): List<JobRead>

    @POST("api/jobs/{id}/cancel")
    suspend fun cancelJob(@Path("id") id: String)

    @DELETE("api/jobs/{id}")
    suspend fun deleteJob(@Path("id") id: String)

    @DELETE("api/jobs")
    suspend fun clearFinishedJobs()

    @GET("api/stats")
    suspend fun stats(): StatsRead

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

    @Multipart
    @POST("api/import")
    suspend fun importEpubs(@Part files: List<MultipartBody.Part>): BookRead
}
