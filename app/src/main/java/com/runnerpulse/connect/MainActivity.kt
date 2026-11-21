package com.runnerpulse.connect

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.Duration

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var client: HealthConnectClient? = null
    private var homePending = 0
    private var homePayload = JSONObject()
    private val corePermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )
    // Nuovi permessi opzionali: se non concessi il collegamento precedente resta verde.
    private val optionalPermissions = setOf(
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    )
    private val permissions get() = corePermissions + optionalPermissions
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
                    granted.containsAll(corePermissions) -> "authorized"
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
        intent.data?.host in setOf("authorize", "revoke", "revoke-auto", "status", "sync", "steps", "sleep", "vitals", "home")

    // Health Connect manages grants persistently. Do not call revokeAllPermissions():
    // on some Android versions it may terminate this app's process.
    private var waitingForHealthSettings = false
    private var healthSettingsOpened = false

    private fun revokeHealthPermissions() {
        // Cambiare sorgente non deve revocare la libreria Health Connect in modo
        // asincrono: su alcuni Honor la coroutine viene cancellata e la WebView
        // riceve JobCancellationException. La revoca esplicita avviene nelle
        // impostazioni di sistema, evitando di bloccare la riconnessione.
        openHealthPermissionSettings()
    }

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
            "revoke-auto" -> revokeHealthPermissions()
            "sync" -> syncExercises()
            "steps" -> syncSteps()
            "sleep" -> syncSleep()
            "vitals" -> syncVitals()
            "home" -> syncHomeHealth()
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

    // Un solo passaggio Activity legge tutti i dati Home, evitando che tre Intents
    // simultanei sovrascrivano i risultati o provochino la chiusura della WebView.
    private fun syncHomeHealth() {
        homePending = 3
        homePayload = JSONObject()
        syncSteps()
        syncSleep()
        syncVitals()
    }
    private fun addHomeResult(part: String, data: JSONObject) {
        if (homePending <= 0) return
        homePayload.put(part, data)
        homePending -= 1
        if (homePending == 0) {
            val target=Intent(this,WebViewLauncherActivity::class.java).apply {
                flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("healthconnect_home",homePayload.toString())
            }
            startActivity(target)
            finish()
        }
    }

    // Daily steps are aggregated by Health Connect across sources; never sum raw
    // Garmin/Fit/HONOR records, which can overlap.
    private fun syncSteps() {
        val health = client ?: run {
            sendStepsResult(JSONObject().put("error", "Health Connect non disponibile")); return
        }
        lifecycleScope.launch {
            try {
                val granted = health.permissionController.getGrantedPermissions()
                if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) {
                    sendStepsResult(JSONObject().put("error", "Permesso Passi non concesso"))
                    return@launch
                }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val days = JSONArray()
                for (offset in 6 downTo 0) {
                    val day = today.minusDays(offset.toLong())
                    val start = day.atStartOfDay(zone).toInstant()
                    val end = if (offset == 0) Instant.now() else day.plusDays(1).atStartOfDay(zone).toInstant()
                    val aggregate = health.aggregate(AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    ))
                    val count = aggregate[StepsRecord.COUNT_TOTAL]
                    days.put(JSONObject().put("date", day.toString())
                        .put("steps", if (count == null) JSONObject.NULL else count))
                }
                sendStepsResult(JSONObject().put("days", days).put("source", "Health Connect"))
            } catch (e: Exception) {
                sendStepsResult(JSONObject().put("error", "Lettura passi non riuscita: " + e.javaClass.simpleName))
            }
        }
    }

    private fun sendStepsResult(result: JSONObject) {
        if (homePending > 0) { addHomeResult("steps",result); return }
        val target = Intent(this, WebViewLauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("healthconnect_steps", result.toString())
        }
        startActivity(target)
        finish()
    }

    private fun syncExercises() {
        val health = client ?: run { sendExerciseResult(JSONObject().put("error", "Health Connect non disponibile")); return }
        lifecycleScope.launch {
            try {
                val granted = health.permissionController.getGrantedPermissions()
                if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted) {
                    sendExerciseResult(JSONObject().put("error", "Autorizza la lettura degli allenamenti in Health Connect"))
                    return@launch
                }
                val now = Instant.now()
                val records = JSONArray()
                var pageToken: String? = null
                var pages = 0
                do {
                    val response = health.readRecords(ReadRecordsRequest(
                        ExerciseSessionRecord::class,
                        TimeRangeFilter.between(now.minus(30, ChronoUnit.DAYS), now),
                        pageSize = 100,
                        pageToken = pageToken
                    ))
                    for (r in response.records) {
                        if (records.length() >= 300) break
                        var distance: Double? = null
                        // Solo distanze realmente presenti per la stessa origine (HONOR ecc.).
                        if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) {
                            try {
                                val summary = health.aggregate(AggregateRequest(
                                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                                    timeRangeFilter = TimeRangeFilter.between(r.startTime, r.endTime),
                                    dataOriginFilter = setOf(DataOrigin(r.metadata.dataOrigin.packageName))
                                ))
                                distance = summary[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                            } catch (_: Exception) { /* Nessun dato, lascia null. */ }
                        }
                        records.put(JSONObject()
                            .put("distanceMeters", distance ?: JSONObject.NULL)
                            .put("id", r.metadata.id)
                            .put("sourcePackage", r.metadata.dataOrigin.packageName)
                            .put("start", r.startTime.toEpochMilli())
                            .put("end", r.endTime.toEpochMilli())
                            .put("exerciseType", r.exerciseType)
                            .put("title", r.title ?: ""))
                    }
                    pageToken = response.pageToken
                    pages++
                } while (pageToken != null && pages < 3 && records.length() < 300)
                sendExerciseResult(JSONObject().put("records", records))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The activity was interrupted (for example by Android navigation).
                // This is not an authorization failure and must not show an internal
                // JobCancellationException to the user.
                if (!isFinishing && !isDestroyed) {
                    sendExerciseResult(JSONObject().put("error", "Lettura interrotta. Riprova dal Profilo."))
                }
            } catch (e: Exception) {
                sendExerciseResult(JSONObject().put("error", "Impossibile leggere gli allenamenti. Riprova tra poco."))
            }
        }
    }

    private fun sendExerciseResult(result: JSONObject) {
        val target = Intent(this, WebViewLauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("healthconnect_sync", result.toString())
        }
        startActivity(target)
        finish()
    }

    // I dati provengono da Health Connect; il nome HONOR non viene attribuito
    // a un record scritto da altre applicazioni.
    private fun syncSleep() {
        val health = client ?: run {
            sendNativeHealthResult("healthconnect_sleep", JSONObject().put("error", "Health Connect non disponibile")); return
        }
        lifecycleScope.launch {
            try {
                val granted = health.permissionController.getGrantedPermissions()
                if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
                    sendNativeHealthResult("healthconnect_sleep", JSONObject().put("error", "Autorizza la lettura del sonno"))
                    return@launch
                }
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val best = mutableMapOf<LocalDate, JSONObject>()
                var pageToken: String? = null
                var pages = 0
                do {
                    val result = health.readRecords(ReadRecordsRequest(
                        SleepSessionRecord::class,
                        TimeRangeFilter.between(today.minusDays(8).atStartOfDay(zone).toInstant(), Instant.now()),
                        pageSize = 100,
                        pageToken = pageToken
                    ))
                    for (r in result.records) {
                        val day = r.endTime.atZone(zone).toLocalDate()
                        if (day.isBefore(today.minusDays(6)) || day.isAfter(today)) continue
                        val length = Duration.between(r.startTime, r.endTime).seconds
                        if (length <= 0 || length > 86400) continue
                        var deep=0L;var rem=0L;var light=0L;var awake=0L
                        for (stage in r.stages) {
                            val sec=Duration.between(stage.startTime, stage.endTime).seconds.coerceAtLeast(0)
                            when (stage.stage) {
                                SleepSessionRecord.STAGE_TYPE_DEEP -> deep += sec
                                SleepSessionRecord.STAGE_TYPE_REM -> rem += sec
                                SleepSessionRecord.STAGE_TYPE_LIGHT -> light += sec
                                SleepSessionRecord.STAGE_TYPE_AWAKE,
                                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += sec
                            }
                        }
                        val asleep = if (deep+rem+light>0) deep+rem+light else (length-awake).coerceAtLeast(0)
                        val record = JSONObject().put("date", day.toString())
                            .put("seconds", asleep)
                            .put("durationRaw", length)
                            .put("sourcePackage", r.metadata.dataOrigin.packageName)
                            .put("deepSeconds", if (deep+rem+light>0) deep else JSONObject.NULL)
                            .put("remSeconds", if (deep+rem+light>0) rem else JSONObject.NULL)
                            .put("lightSeconds", if (deep+rem+light>0) light else JSONObject.NULL)
                            .put("awakeSeconds", if (deep+rem+light>0) awake else JSONObject.NULL)
                        val old = best[day]
                        val provider=r.metadata.dataOrigin.packageName.lowercase()
                        val isHonor=provider.contains("honor")
                        val oldHonor=old?.optString("sourcePackage","")?.lowercase()?.contains("honor") == true
                        if (old==null || (isHonor && !oldHonor) || (isHonor==oldHonor && length>old.optLong("durationRaw",0))) best[day]=record
                    }
                    pageToken=result.pageToken;pages++
                } while (pageToken != null && pages < 3)
                val days=JSONArray()
                for (offset in 6 downTo 0) {
                    val day=today.minusDays(offset.toLong())
                    days.put(best[day] ?: JSONObject().put("date",day.toString())
                        .put("seconds",JSONObject.NULL).put("deepSeconds",JSONObject.NULL)
                        .put("remSeconds",JSONObject.NULL).put("lightSeconds",JSONObject.NULL)
                        .put("awakeSeconds",JSONObject.NULL).put("sourcePackage",""))
                }
                sendNativeHealthResult("healthconnect_sleep", JSONObject().put("days",days).put("source","Health Connect"))
            } catch (e: Exception) {
                sendNativeHealthResult("healthconnect_sleep", JSONObject().put("error", "Lettura sonno non riuscita: " + e.javaClass.simpleName))
            }
        }
    }

    private fun syncVitals() {
        val health = client ?: run {
            sendNativeHealthResult("healthconnect_vitals", JSONObject().put("error", "Health Connect non disponibile")); return
        }
        lifecycleScope.launch {
            try {
                val granted=health.permissionController.getGrantedPermissions()
                val output=JSONObject().put("vo2max",JSONObject.NULL).put("restingHeartRate",JSONObject.NULL)
                val from=Instant.now().minus(30,ChronoUnit.DAYS)
                if (HealthPermission.getReadPermission(Vo2MaxRecord::class) in granted) {
                    try {
                        val response=health.readRecords(ReadRecordsRequest(Vo2MaxRecord::class,
                            TimeRangeFilter.between(from,Instant.now()),pageSize=100))
                        val record=response.records.maxByOrNull { it.time }
                        if (record != null) {
                            output.put("vo2max",record.vo2MillilitersPerMinuteKilogram)
                            output.put("vo2SourcePackage",record.metadata.dataOrigin.packageName)
                            output.put("vo2ObservedAt",record.time.toEpochMilli())
                        }
                    } catch (_: Exception) { }
                }
                if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
                    try {
                        val response=health.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class,
                            TimeRangeFilter.between(from,Instant.now()),pageSize=100))
                        val record=response.records.maxByOrNull { it.time }
                        if (record != null) {
                            output.put("restingHeartRate",record.beatsPerMinute)
                            output.put("heartSourcePackage",record.metadata.dataOrigin.packageName)
                            output.put("heartObservedAt",record.time.toEpochMilli())
                        }
                    } catch (_: Exception) { }
                }
                sendNativeHealthResult("healthconnect_vitals",output)
            } catch (e: Exception) {
                sendNativeHealthResult("healthconnect_vitals",JSONObject().put("error", "Lettura parametri non riuscita"))
            }
        }
    }

    private fun sendNativeHealthResult(extraName: String, result: JSONObject) {
        if (homePending > 0) {
            addHomeResult(if (extraName == "healthconnect_sleep") "sleep" else "vitals",result)
            return
        }
        val target = Intent(this, WebViewLauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(extraName, result.toString())
        }
        startActivity(target)
        finish()
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
