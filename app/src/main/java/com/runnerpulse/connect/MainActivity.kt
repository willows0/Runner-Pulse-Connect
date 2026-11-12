package com.runnerpulse.connect

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
    private var webOpened = false

    private fun openWeb() {
        if (webOpened) return
        webOpened = true
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://runner-pulse-web.onrender.com/")))
        } catch (e: Exception) {
            webOpened = false
            status.text = "Impossibile aprire Runner Pulse Web: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun checkPermissionsAndContinue() {
        val health = client ?: return
        lifecycleScope.launch {
            try {
                val granted = health.permissionController.getGrantedPermissions()
                if (granted.containsAll(permissions)) {
                    status.text = "Autorizzazioni concesse: 4 su 4. Apertura del sito…"
                    openWeb()
                } else {
                    openPermissionScreen()
                }
            } catch (e: Exception) {
                status.text = "Verifica autorizzazioni non riuscita: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private val permissionRequest = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(permissions)) {
            status.text = "Autorizzazioni concesse: 4 su 4. Apertura del sito…"
            openWeb()
        } else {
            status.text = "Autorizzazioni concesse: ${granted.intersect(permissions).size} su 4. Completa le autorizzazioni per continuare."
        }
    }

    private fun isAuthorizeLink(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_VIEW &&
        intent.data?.scheme == "runnerpulseconnect" &&
        intent.data?.host == "authorize"

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
        webOpened = false
        if (isAuthorizeLink(intent)) checkPermissionsAndContinue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 42, 28, 28)
            setBackgroundColor(Color.rgb(16, 35, 58))
        }
        val title = TextView(this).apply {
            text = "Runner Pulse Connect"
            textSize = 26f
            setTextColor(Color.WHITE)
        }
        panel.addView(title)
        status = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, 32, 0, 24)
            text = "Verifica Health Connect in corso…"
        }
        panel.addView(status)
        fun addButton(label: String, action: () -> Unit) {
            panel.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        addButton("AUTORIZZA HEALTH CONNECT") {
            openPermissionScreen()
        }
        addButton("LEGGI DATI REALI") { readData() }
        addButton("APRI RUNNER PULSE WEB") { openWeb() }
        setContentView(panel)
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
        // Se tutti i permessi sono già concessi, il sito si apre direttamente.
        // In caso contrario, un deep link dal sito apre la richiesta di autorizzazione.
        status.post {
            val health = client
            if (health != null) {
                lifecycleScope.launch {
                    try {
                        val granted = health.permissionController.getGrantedPermissions()
                        if (granted.containsAll(permissions)) {
                            openWeb()
                        } else if (isAuthorizeLink(intent)) {
                            openPermissionScreen()
                        } else {
                            status.text = "Autorizzazioni concesse: ${granted.intersect(permissions).size} su 4. Premi AUTORIZZA HEALTH CONNECT."
                        }
                    } catch (e: Exception) {
                        status.text = "Verifica permessi non riuscita: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
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
