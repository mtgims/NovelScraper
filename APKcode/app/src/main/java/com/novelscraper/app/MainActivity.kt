package com.novelscraper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelscraper.app.ui.AuthState
import com.novelscraper.app.ui.AuthViewModel
import com.novelscraper.app.ui.screen.LibraryScreen
import com.novelscraper.app.ui.screen.LoginScreen
import com.novelscraper.app.ui.theme.NovelScraperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NovelScraperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { inner ->
                        AppRoot(Modifier.padding(inner))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot(modifier: Modifier = Modifier) {
    val vm: AuthViewModel = viewModel()
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            is AuthState.Loading ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            is AuthState.SignedOut ->
                LoginScreen(
                    baseUrl = vm.baseUrl,
                    busy = busy,
                    error = error,
                    onLogin = vm::login,
                    onSetBaseUrl = vm::setBaseUrl,
                )
            is AuthState.SignedIn ->
                LibraryScreen(username = s.user.username, onLogout = vm::logout)
        }
    }
}
