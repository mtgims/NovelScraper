package com.novelscraper.app.ui.screen

import com.novelscraper.app.platform.PlatformBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun RegisterScreen(
    busy: Boolean,
    error: String?,
    openSignup: Boolean,
    onRegister: (username: String, password: String, invite: String) -> Unit,
    onBack: () -> Unit,
) {
    PlatformBackHandler(onBack = onBack)
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Create account", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (openSignup) "Choose a username and password"
            else "You'll need an invite code",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        val w = Modifier.widthIn(max = 420.dp).fillMaxWidth()
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Username") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = w,
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            modifier = w.padding(top = 12.dp),
        )
        if (!openSignup) {
            OutlinedTextField(
                value = invite, onValueChange = { invite = it },
                label = { Text("Invite code") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = w.padding(top = 12.dp),
            )
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        Button(
            onClick = { onRegister(username, password, invite) },
            enabled = !busy && username.isNotBlank() && password.isNotBlank(),
            modifier = w.padding(top = 20.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
            Text("Create account")
        }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            Text("Back to sign in")
        }
    }
}
