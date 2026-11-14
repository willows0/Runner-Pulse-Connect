package com.runnerpulse.connect

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var client: HealthConnectClient? = null
    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )
    private val permissionRequest = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // La risposta del sistema e' verificata di nuovo prima di tornare al sito.
        returnToProfile()
    }

    private fun returnToProfile() {
        val health = client
        if (health == null) {
            openWebAt("healthconnect=unavailable")
            return
        }
        lifecycleScope.launch {
            try {
                val granted = health.permissionController.getGrantedPermissions()
                val result = when {
                    granted.containsAll(permissions) -> "authorized"
                    granted.isNotEmpty() -> "partial"
                    else -> "denied"
                }
                openWebAt("healthconnect=$result")
            } catch (e: Exception) {
                openWebAt("healthconnect=error")
            }
        }
    }

    // Chrome Custom Tabs mantiene l’accesso Google del browser.
    // Una WebView tradizionale non è compatibile con alcuni flussi Google OAuth.
    private fun openSite(uri: Uri) {
        try {
            CustomTabsIntent.Builder().setShowTitle(false).build().launchUrl(this, uri)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    private fun openWebAt(query: String) {
        val result = query.substringAfter("healthconnect=", "error")
        val target = Intent(this, WebViewLauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("healthconnect_result", result)
        }
        startActivity(target)
        finish()
    }

    private fun openWeb() {
        try {
            openSite(Uri.parse("https://runner-pulse-web.onrender.com/"))
            finish()
        } catch (e: Exception) {
            status.text = "Impossibile aprire il sito: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun isAuthorizeLink(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW &&
        intent.data?.scheme == "runnerpulseconnect" &&
        intent.data?.host in setOf("authorize", "revoke", "status")

    // Health Connect manages grants persistently. Do not call revokeAllPermissions():
    // on some Android versions it may terminate this app's process.
    private var waitingForHealthSettings = false
    private var healthSettingsOpened = false

    private fun openHealthPermissionSettings() {
        try {
            val settingsIntent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            if (settingsIntent.resolveActivity(packageManager) != null) {
                waitingForHealthSettings = true
                healthSettingsOpened = false
                startActivity(settingsIntent)
            } else {
                // Fallback: Android app details allow access to the Health Connect permissions entry.
                waitingForHealthSettings = true
                healthSettingsOpened = false
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {
            waitingForHealthSettings = false
            openWebAt("healthconnect=error")
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForHealthSettings) {
            if (!healthSettingsOpened) {
                healthSettingsOpened = true
            } else {
                waitingForHealthSettings = false
                healthSettingsOpened = false
                // Read actual permissions when returning from system settings.
                returnToProfile()
            }
        }
    }

    private fun handleHealthAction(intent: Intent?) {
        when (intent?.data?.host) {
            "revoke" -> openHealthPermissionSettings()
            "status" -> returnToProfile()
            "authorize" -> openPermissionScreen()
            else -> openWeb()
        }
    }

    private fun openPermissionScreen() {
        if (client == null) {
            status.text = "Health Connect non disponibile."
            return
        }
        try {
            permissionRequest.launch(permissions)
        } catch (e: Exception) {
            status.text = "Impossibile aprire autorizzazioni: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAuthorizeLink(intent)) handleHealthAction(intent) else openWeb()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity di servizio invisibile: la WebView del Profilo resta sotto.
        // Non mostrare piu' la vecchia schermata con i tre pulsanti.
        status = TextView(this)
        try {
            when (HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata")) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    client = HealthConnectClient.getOrCreate(this)
                    status.text = "Health Connect disponibile. Premi AUTORIZZA HEALTH CONNECT."
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    status.text = "Health Connect richiede un aggiornamento."
                else -> status.text = "Health Connect non disponibile."
            }
        } catch (e: Exception) {
            status.text = "Errore inizializzazione: ${e.message ?: e.javaClass.simpleName}"
        }
        if (isAuthorizeLink(intent)) {
            // Solo il pulsante Health Connect del sito richiede i permessi.
            handleHealthAction(intent)
        } else {
            // L’icona dell’app apre sempre il sito, anche senza permessi.
            openWeb()
        }
    }

    private fun readData() {
        val health = client ?: run { status.text = "Health Connect non disponibile."; return }
        status.text = "Lettura dei dati autorizzati…"
        lifecycleScope.launch {
            try {
                val granted = health.permissionController.getGrantedPermissions()
                if (granted.isEmpty()) {
                    status.text = "Prima premi AUTORIZZA HEALTH CONNECT."
                    return@launch
                }
                val now = Instant.now()
                val output = mutableListOf("Dati REALI disponibili sul telefono:")
                if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                    val result = health.readRecords(ReadRecordsRequest(
                        StepsRecord::class,
                        TimeRangeFilter.between(now.minus(1, ChronoUnit.DAYS), now),
                        pageSize = 1000
                    ))
                    output += "Passi (ultime 24 ore, max 1000 record): ${result.records.sumOf { it.count }}"
                } else output += "Passi: permesso non concesso"
                if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
                    val result = health.readRecords(ReadRecordsRequest(
                        SleepSessionRecord::class,
                        TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now),
                        pageSize = 1000
                    ))
                    output += "Sessioni sonno (7 giorni): ${result.records.size}"
                } else output += "Sonno: permesso non concesso"
                if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
                    val result = health.readRecords(ReadRecordsRequest(
                        HeartRateRecord::class,
                        TimeRangeFilter.between(now.minus(1, ChronoUnit.DAYS), now),
                        pageSize = 1000
                    ))
                    output += "Record frequenza cardiaca (24 ore): ${result.records.size}"
                } else output += "Frequenza cardiaca: permesso non concesso"
                if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                    val result = health.readRecords(ReadRecordsRequest(
                        ExerciseSessionRecord::class,
                        TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now),
                        pageSize = 1000
                    ))
                    output += "Allenamenti (7 giorni): ${result.records.size}"
                } else output += "Allenamenti: permesso non concesso"
                output += "Nessun dato inviato a Firestore."
                status.text = output.joinToString("\n")
            } catch (e: Exception) {
                status.text = "Lettura non riuscita: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }
}
