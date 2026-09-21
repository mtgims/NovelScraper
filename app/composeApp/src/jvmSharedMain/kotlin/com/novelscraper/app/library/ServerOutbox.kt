package com.novelscraper.app.library

import com.novelscraper.app.data.BookCollectionsUpdate
import com.novelscraper.app.data.BookUpdate
import com.novelscraper.app.data.CollectionUpdate
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.platform.Log
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** The calls a queued server change (a row of server_outbox) can be. */
internal object ServerOutbox {
    const val PROGRESS = "progress"
    const val RATING = "rating"
    const val BOOK_COLLECTIONS = "book_collections"
    const val RENAME_COLLECTION = "rename_collection"
    const val DELETE_COLLECTION = "delete_collection"
    const val DELETE_BOOK = "delete_book"

    enum class Result {
        /** Sent. */
        Done,
        /** Refused for good (the novel or shelf is gone there): drop it. */
        Dropped,
        /** Couldn't be sent now (offline, signed out, server error): keep it. */
        Later,
    }

    suspend fun send(server: ServerOrigin, json: Json, kind: String, target: Int, body: String): Result = try {
        when (kind) {
            PROGRESS -> server.putProgress(target, json.decodeFromString(ProgressUpdate.serializer(), body))
            RATING -> server.editBook(target, json.decodeFromString(BookUpdate.serializer(), body))
            BOOK_COLLECTIONS -> server.setBookCollections(target, json.decodeFromString(BookCollectionsUpdate.serializer(), body))
            RENAME_COLLECTION -> server.updateCollection(target, json.decodeFromString(CollectionUpdate.serializer(), body))
            DELETE_COLLECTION -> server.deleteCollection(target)
            DELETE_BOOK -> server.deleteBook(target)
            else -> Log.w("Outbox", "unknown change '$kind', dropped")
        }
        Result.Done
    } catch (e: HttpException) {
        // 401/403: signed out or not allowed right now; 404/400/409/422: this
        // change can never apply.
        if (e.code() in 400..499 && e.code() != 401 && e.code() != 403 && e.code() != 408 && e.code() != 429) {
            Log.w("Outbox", "$kind for $target refused (${e.code()}), dropped")
            Result.Dropped
        } else Result.Later
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Later
    }
}
