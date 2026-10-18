package com.fantasyanalyzer.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnLogin
import com.fantasyanalyzer.data.LeagueRef
import com.fantasyanalyzer.data.Settings
import com.fantasyanalyzer.data.AlertSettings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    draft: Settings,
    leagueSearch: LeagueSearch,
    onChange: (Settings) -> Unit,
    onFindLeagues: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    alerts: AlertSettings,
    onAlertsChange: (AlertSettings) -> Unit,
) {
    BackHandler(onBack = onBack)
    var showManual by remember { mutableStateOf(false) }
    var settingsTab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
      Column(Modifier.padding(pad).fillMaxSize()) {
        TabRow(selectedTabIndex = settingsTab) {
            Tab(selected = settingsTab == 0, onClick = { settingsTab = 0 }, text = { Text("League") })
            Tab(selected = settingsTab == 1, onClick = { settingsTab = 1 }, text = { Text("Notifications") })
        }
        if (settingsTab == 1) {
            NotificationSettings(alerts, onAlertsChange, available = !draft.useDemo && draft.hasLeague)
        } else
        Column(
            Modifier.imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                    Text("Explore the app with a made-up league.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = draft.useDemo, onCheckedChange = { onChange(draft.copy(useDemo = it)) })
            }
            HorizontalDivider()

            Text("Your ESPN league", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.hasCookies) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = Good)
                            Text("  Signed in to ESPN", Modifier.weight(1f))
                            TextButton(onClick = onSignOut) { Text("Sign out") }
                        }
                        LeaguePicker(draft, leagueSearch, onFindLeagues) { ref ->
                            onChange(draft.copy(leagueId = ref.leagueId.toString(), year = ref.season, useDemo = false))
                        }
                    } else {
                        Text(
                            "Private leagues need you to sign in to ESPN. After signing in, the app " +
                                "finds your leagues for you.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onSignIn, Modifier.fillMaxWidth()) { Text("Sign in with ESPN") }
                    }
                }
            }

            OutlinedTextField(
                value = draft.leagueId,
                onValueChange = { v ->
                    // Accept a pasted league URL too.
                    val id = EspnLogin.leagueIdFrom(v) ?: v.filter { it.isDigit() }
                    onChange(draft.copy(leagueId = id))
                },
                label = { Text("League ID") },
                supportingText = { Text("The leagueId number in your league's URL. Pasting the URL works too.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.year.toString(),
                onValueChange = { v -> v.filter { it.isDigit() }.take(4).toIntOrNull()?.let { onChange(draft.copy(year = it)) } },
                label = { Text("Season") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = { showManual = !showManual }) {
                Text(if (showManual) "Hide manual cookie entry" else "Enter cookies manually instead")
            }
            if (showManual) {
                Text(
                    "From a desktop browser signed in to ESPN: developer tools → Application/Storage → " +
                        "Cookies → fantasy.espn.com. Copy espn_s2 and SWID.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = draft.espnS2, onValueChange = { onChange(draft.copy(espnS2 = it.trim())) },
                    label = { Text("espn_s2") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.swid, onValueChange = { onChange(draft.copy(swid = it.trim())) },
                    label = { Text("SWID") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "Your ESPN cookies are stored encrypted on this phone and are only ever sent to ESPN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSave,
                enabled = draft.useDemo || draft.hasLeague,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (draft.useDemo) "Show demo league" else "Load my league") }
        }
      }
    }
}

/** Game-day notifications: what to be told about. Changes save right away. */
@Composable
private fun NotificationSettings(a: AlertSettings, onChange: (AlertSettings) -> Unit, available: Boolean) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onChange(a.copy(enabled = true))
    }
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!available) {
            Text("Connect your ESPN league to turn on notifications.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        AlertSwitch("Game-day notifications", "Alerts about your matchup while games are on.", a.enabled) { on ->
            if (on && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onChange(a.copy(enabled = on))
            }
        }
        if (!a.enabled) return@Column
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("SCORING", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        AlertSwitch("Player scored", "One alert with the play, the new matchup score and your win chance.", a.playerScored) {
            onChange(a.copy(playerScored = it))
        }
        if (a.playerScored) {
            Text("Counts as a score when a player gains at least", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3, 4, 6).forEach { t ->
                    FilterChip(selected = a.scoreThreshold == t, onClick = { onChange(a.copy(scoreThreshold = t)) },
                        label = { Text("$t pts" + if (t == 6) " (TDs)" else "") })
                }
            }
            AlertSwitch("Include my opponent's players", null, a.includeOpponent) { onChange(a.copy(includeOpponent = it)) }
        }
        AlertSwitch("Red zone", "When one of your players' teams gets inside the 20.", a.redZone) { onChange(a.copy(redZone = it)) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("MATCHUP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        AlertSwitch("Lead changes", null, a.leadChange) { onChange(a.copy(leadChange = it)) }
        AlertSwitch("Win chance crosses 50%", null, a.winSwing) { onChange(a.copy(winSwing = it)) }
        AlertSwitch("Final result", null, a.finalResult) { onChange(a.copy(finalResult = it)) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("LINEUP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        AlertSwitch("Starter ruled out or doubtful", "Before their game starts.", a.injuries) { onChange(a.copy(injuries = it)) }
        AlertSwitch("Lineup check before kickoff", "About an hour before a game if a starter is out, on bye, or a slot is empty.",
            a.lineupReminder) { onChange(a.copy(lineupReminder = it)) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        AlertSwitch(
            "Instant alerts",
            "Checks every minute during games and shows a \"Live scoring\" notification while it does. Off, or if Android " +
                "won't allow it while the app is closed, checks run every 15 minutes.",
            a.instant,
        ) { onChange(a.copy(instant = it)) }
    }
}

@Composable
private fun AlertSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LeaguePicker(draft: Settings, search: LeagueSearch, onFind: () -> Unit, onPick: (LeagueRef) -> Unit) {
    when (search) {
        LeagueSearch.Idle -> OutlinedButton(onClick = onFind, Modifier.fillMaxWidth()) { Text("Find my leagues") }
        LeagueSearch.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp))
            Text("Looking up your leagues…")
        }
        is LeagueSearch.Failed -> Column {
            Text(search.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onFind) { Text("Try again") }
        }
        is LeagueSearch.Found -> Column {
            Text("Pick your league", style = MaterialTheme.typography.labelLarge)
            search.leagues.forEach { ref ->
                val selected = draft.leagueId == ref.leagueId.toString() && draft.year == ref.season
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(ref) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onPick(ref) })
                    Column(Modifier.weight(1f)) {
                        Text(ref.leagueName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOf(ref.season.takeIf { it > 0 }?.toString(), ref.teamName.ifBlank { null })
                                .filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ESPN's own sign-in page in a WebView. Once ESPN sets the espn_s2 cookie we
 * have what the API needs; if the user then opens their league we also get
 * its ID from the URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EspnLoginScreen(onDone: (EspnLogin) -> Unit, onCancel: () -> Unit) {
    var login by remember { mutableStateOf(EspnLogin()) }
    // Plain holder, not state: nothing needs to recompose when it's set.
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    BackHandler {
        val wv = webViewRef[0]
        if (wv != null && wv.canGoBack()) wv.goBack() else onCancel()
    }

    fun refresh(url: String?) {
        val cm = CookieManager.getInstance()
        login = login.update(COOKIE_HOSTS.map { cm.getCookie(it) }, url)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sign in to ESPN")
                        Text(
                            when {
                                !login.signedIn -> "Log in with your ESPN account"
                                login.leagueId == null -> "Signed in ✓ Tap Done"
                                else -> "Signed in ✓ League ${login.leagueId} ✓"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") }
                },
                actions = {
                    if (login.signedIn) {
                        if (login.leagueId != null) {
                            Button(onClick = { onDone(login) }, Modifier.padding(end = 8.dp)) { Text("Use") }
                        } else {
                            Button(onClick = { onDone(login) }, Modifier.padding(end = 8.dp)) { Text("Done") }
                        }
                    }
                },
            )
        },
    ) { pad ->
        AndroidView(
            modifier = Modifier.padding(pad).fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        // The fantasy site is a single-page app, so in-app navigation
                        // only shows up here, not in onPageFinished.
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = refresh(url)
                        override fun onPageFinished(view: WebView, url: String?) = refresh(url)
                    }
                    loadUrl(START_URL)
                    webViewRef[0] = this
                }
            },
        )
    }
}

// ESPN's sign-in page, which then lists the account's teams.
private const val START_URL = "https://fantasy.espn.com/find-my-team"
// The API host first, so the saved cookies are the ones ESPN's API will see.
private val COOKIE_HOSTS = listOf(EspnClient.API_ORIGIN, "https://fantasy.espn.com", "https://www.espn.com", "https://espn.com")
