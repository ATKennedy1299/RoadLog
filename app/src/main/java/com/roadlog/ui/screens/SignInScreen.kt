package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.roadlog.R
import com.roadlog.ui.SignInViewModel
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * RoadLog's own sign-in screen — Firebase is purely the backend behind it
 * and is never named or shown anywhere in this UI. The "Sign in with
 * Google" button uses Google's required styling/text for that option
 * specifically, since it is signing in with a Google account.
 */
@Composable
fun SignInScreen(viewModel: SignInViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun launchGoogleSignIn() {
        viewModel.beginGoogleSignIn()
        coroutineScope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val option = GetSignInWithGoogleOption.Builder(
                    serverClientId = context.getString(R.string.default_web_client_id)
                ).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val response = credentialManager.getCredential(request = request, context = context)
                val credential = response.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    viewModel.signInWithGoogleIdToken(googleIdTokenCredential.idToken)
                } else {
                    viewModel.reportGoogleSignInFailure(
                        "Unexpected credential type: ${credential::class.simpleName}"
                    )
                }
            } catch (e: GetCredentialCancellationException) {
                // User backed out of the account picker — not a failure.
                viewModel.cancelGoogleSignIn()
            } catch (e: GetCredentialException) {
                Log.e("SignInScreen", "Google sign-in failed", e)
                viewModel.reportGoogleSignInFailure(
                    "Google sign-in failed: ${e::class.simpleName}: ${e.message ?: "no message"}"
                )
            } catch (e: GoogleIdTokenParsingException) {
                Log.e("SignInScreen", "Couldn't parse Google credential", e)
                viewModel.reportGoogleSignInFailure(
                    "Couldn't read Google credential: ${e.message ?: "no message"}"
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "‹ BACK",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "ROADLOG",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Sign in to share live location with friends",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; viewModel.clearError() },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; viewModel.clearError() },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = AccentRed, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.signIn(email, password) },
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("SIGN IN")
        }
        TextButton(
            onClick = { viewModel.signUp(email, password) },
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create an account", color = TextSecondary)
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { launchGoogleSignIn() },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = Color.White),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Sign in with Google")
        }

        if (isLoading) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                color = AccentGreen,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = AccentGreen,
    unfocusedBorderColor = TextSecondary,
    focusedLabelColor = AccentGreen,
    unfocusedLabelColor = TextSecondary,
    cursorColor = AccentGreen
)
