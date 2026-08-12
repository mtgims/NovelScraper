package com.novelscraper.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Placeholder reader. Milestone 5 renders chapter HTML
 *  (GET /api/books/{id}/chapters/{position}) with prev/next + progress sync. */
@Composable
fun ReaderScreen(bookId: Int, position: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Reader for book #$bookId, chapter $position — milestone 5",
            style = MaterialTheme.typography.bodyMedium)
    }
}
