package com.example.deathstrandingvelo

import kotlin.math.abs
import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh

// --- НОВЫЕ КЛАССЫ ДЛЯ ДОЖДЯ И ТВАРЕЙ ---
data class BTZone(val center: GeoPoint, val radius: Double = 50.0) // Радиус 50м = Диаметр 100м
data class TimefallZone(val center: GeoPoint, val radius: Double = 250.0, val btZones: List<BTZone>) // Радиус 250м = Диаметр 500м

data class CargoTemplate(
    val name: String,
    val description: String,
    val weightKg: Double,
    val isFragile: Boolean,
    val baseXp: Int = 100,
    val category: String = "Материалы",
    val spawnChance: Int = 50,
    val baseMoney: Int = 50
)

data class CargoItem(
    val id: Int,
    val name: String,
    val description: String,
    val weightKg: Double,
    val isFragile: Boolean,
    val location: GeoPoint,
    val xpReward: Int,
    val moneyReward: Int,
    var status: CargoStatus = CargoStatus.PENDING,
    var health: Double = 100.0 // НОВОЕ: Состояние груза (0-100%)
)

data class LevelTemplate(val level: Int, val title: String, val requiredXp: Int)
data class BikeUpgrade(val level: Int, val name: String, val maxWeightKg: Double, val costMoney: Int)
enum class CargoStatus { PENDING, COLLECTED, CANCELED }
data class CitySector(val id: Int, val center: GeoPoint, var isActive: Boolean = true, var visitCount: Int = 0)

data class RouteStep(
    val location: GeoPoint,
    val instruction: String,
    var announced500: Boolean = false,
    var announced100: Boolean = false,
    var announcedNow: Boolean = false
)

data class OsrmResult(val path: List<GeoPoint>, val distanceMeters: Double, val steps: List<RouteStep>, val waypointIndices: List<Int> = emptyList())

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val perms = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val locationPermissionsState = rememberMultiplePermissionsState(perms)
                    if (locationPermissionsState.allPermissionsGranted) {
                        AppNavigation()
                    } else {
                        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("Для навигатора нужны GPS и Уведомления.")
                            Button(onClick = { locationPermissionsState.launchMultiplePermissionRequest() }) { Text("Активировать") }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(android.content.Intent(this, NavService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val dbHelper = remember { DatabaseHelper(context) }
    var baseLocation by remember { mutableStateOf(loadBaseLocation(context)) }

    var bikeCargos by remember { mutableStateOf(dbHelper.getCargoByStatus("ON_BIKE")) }
    var cargoList by remember { mutableStateOf(bikeCargos) }

    LaunchedEffect(Unit) {
        bikeCargos = dbHelper.getCargoByStatus("ON_BIKE")
        cargoList = bikeCargos
    }

    val refreshBikeCargos = {
        bikeCargos = dbHelper.getCargoByStatus("ON_BIKE")
        cargoList = bikeCargos
    }

    val cargoTemplates by remember { mutableStateOf(
        try {
            val jsonString = context.assets.open("cargo_db.json").bufferedReader().use { it.readText() }
            val templateType = object : TypeToken<List<CargoTemplate>>() {}.type
            Gson().fromJson<List<CargoTemplate>>(jsonString, templateType)
        } catch (e: Exception) {
            listOf(CargoTemplate("Аварийный груз", "База данных не найдена.", 5.0, false, 100, "Материалы", 50, 50))
        }
    )}

    var playerXp by remember { mutableStateOf(loadPlayerXp(context)) }
    var playerMoney by remember { mutableStateOf(loadPlayerMoney(context)) }

    var distanceSinceLastEventCheck by remember { mutableStateOf(0.0) }
    var currentEventChance by remember { mutableStateOf(10) }
    var pendingSideQuestLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var showSideQuestDialog by remember { mutableStateOf(false) }

    // --- ПЕРЕМЕННЫЕ ДЛЯ ДОЖДЯ И ТВАРЕЙ ---
    var timefallZones by remember { mutableStateOf<List<TimefallZone>>(emptyList()) }
    var inTimefall by remember { mutableStateOf(false) }
    var inBtZone by remember { mutableStateOf(false) }
    var lastTimefallDamageTime by remember { mutableStateOf(0L) }
    var lastBtAttackTime by remember { mutableStateOf(0L) }

    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val focusRequest = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
    }

    val levelsDb by remember { mutableStateOf(loadLevelsDb(context)) }

    val updateNotification = { xp: Int ->
        val currentLevel = levelsDb.lastOrNull { xp >= it.requiredXp } ?: levelsDb.first()
        val nextLevel = levelsDb.firstOrNull { it.level == currentLevel.level + 1 }

        val title = "⭐ ${currentLevel.title}"
        val text = if (nextLevel != null) "Опыт: $xp / ${nextLevel.requiredXp} XP" else "Опыт: $xp XP (Максимум)"

        val serviceIntent = android.content.Intent(context, NavService::class.java).apply {
            putExtra("TITLE", title)
            putExtra("TEXT", text)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
    var isTtsReady by remember { mutableStateOf(false) }
    var ttsPitch by remember { mutableStateOf(0.8f) }
    var ttsRate by remember { mutableStateOf(0.9f) }
    var ttsDiagText by remember { mutableStateOf("") }
    var availableVoices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var selectedVoiceName by remember { mutableStateOf("") }
    var isVoiceDropdownExpanded by remember { mutableStateOf(false) }

    val tts = remember { TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) isTtsReady = true } }

    LaunchedEffect(isTtsReady) {
        if (isTtsReady) {
            tts.setLanguage(Locale("ru", "RU"))
            try {
                val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
                ttsPitch = prefs.getFloat("tts_pitch", 0.8f)
                ttsRate = prefs.getFloat("tts_rate", 0.9f)
                tts.setPitch(ttsPitch)
                tts.setSpeechRate(ttsRate)

                val voices = tts.voices?.filter { it.locale.language == "ru" } ?: emptyList()
                availableVoices = voices
                val savedVoiceName = prefs.getString("saved_voice", null)
                val voiceToSet = voices.find { it.name == savedVoiceName } ?: voices.firstOrNull { !it.name.contains("network") } ?: voices.firstOrNull()
                if (voiceToSet != null) {
                    tts.voice = voiceToSet
                    selectedVoiceName = voiceToSet.name
                }
            } catch (e: Exception) { e.printStackTrace() }

            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { audioManager.requestAudioFocus(focusRequest) }
                override fun onDone(utteranceId: String?) { audioManager.abandonAudioFocusRequest(focusRequest) }
                override fun onError(utteranceId: String?) { audioManager.abandonAudioFocusRequest(focusRequest) }
            })
        }
    }

    var visitedPoints by remember { mutableStateOf<List<GeoPoint>>(loadVisitedPoints(context)) }
    var citySectors by remember { mutableStateOf<List<CitySector>>(loadSectors(context)) }
    var isGridEditMode by remember { mutableStateOf(false) }
    val brushRadiusState = remember { mutableStateOf(433.0) }
    var isFollowMode by remember { mutableStateOf(false) }
    var currentBearing by remember { mutableStateOf(0f) }
    var shouldInitCamera by remember { mutableStateOf(false) }
    var userPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }

    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var navSteps by remember { mutableStateOf<List<RouteStep>>(emptyList()) }
    var lastOffRouteWarningTime by remember { mutableStateOf(0L) }

    var totalDistanceMeters by remember { mutableStateOf(0.0) }
    var distanceTraveledMeters by remember { mutableStateOf(0.0) }
    var previousLocation by remember { mutableStateOf<android.location.Location?>(null) }

    var isRouteBuilt by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var hasZoomedToRoute by remember { mutableStateOf(false) }

    var showListDialog by remember { mutableStateOf(false) }
    var selectedCargo by remember { mutableStateOf<CargoItem?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    Configuration.getInstance().userAgentValue = context.packageName

    val announceNext = {
        val nextCargo = cargoList.firstOrNull { it.status == CargoStatus.PENDING }
        if (nextCargo != null) {
            val fragileWarning = if (nextCargo.isFragile) "Груз хрупкий, требует внимания." else ""
            tts.speak("Следующий груз: ${nextCargo.name}. ${nextCargo.description}. $fragileWarning", TextToSpeech.QUEUE_ADD, null, "nav")
        } else {
            tts.speak("Все грузы обработаны. Заказ выполнен, возвращайтесь на базу.", TextToSpeech.QUEUE_ADD, null, "nav")
        }
    }

    val processCargo = { cargoId: Int, newStatus: CargoStatus ->
        val targetCargo = cargoList.find { it.id == cargoId }
        cargoList = cargoList.map { if (it.id == cargoId) it.copy(status = newStatus) else it }

        if (newStatus == CargoStatus.COLLECTED && targetCargo != null) {
            addTotalDeliveries(context, 1)
            val sector = citySectors.minByOrNull { it.center.distanceToAsDouble(targetCargo.location) }
            if (sector != null && sector.center.distanceToAsDouble(targetCargo.location) < 600.0) {
                citySectors = citySectors.map { if (it.id == sector.id) it.copy(visitCount = it.visitCount + 1) else it }
                saveSectors(context, citySectors)
            }

            val oldLevel = levelsDb.lastOrNull { playerXp >= it.requiredXp } ?: levelsDb.first()

            // СЧИТАЕМ НАГРАДУ С УЧЕТОМ УРОНА
            val healthMult = targetCargo.health / 100.0
            val finalXp = (targetCargo.xpReward * healthMult).roundToInt()
            val finalMoney = (targetCargo.moneyReward * healthMult).roundToInt()

            playerXp += finalXp
            playerMoney += finalMoney
            savePlayerXp(context, playerXp)
            savePlayerMoney(context, playerMoney)
            updateNotification(playerXp)

            val newLevel = levelsDb.lastOrNull { playerXp >= it.requiredXp } ?: levelsDb.first()

            val conditionText = "Состояние: ${targetCargo.health.toInt()} процентов."

            if (newLevel.level > oldLevel.level) {
                tts.speak("Груз доставлен. $conditionText Уровень повышен! Теперь вы: ${newLevel.title}.", TextToSpeech.QUEUE_FLUSH, null, "nav")
            } else {
                tts.speak("Груз доставлен. $conditionText Получено $finalXp опыта и $finalMoney кредитов.", TextToSpeech.QUEUE_FLUSH, null, "nav")
            }

            val newHistory = visitedPoints + targetCargo.location
            visitedPoints = newHistory
            saveVisitedPoints(context, newHistory)

        } else if (newStatus == CargoStatus.CANCELED) {
            tts.speak("Груз отменен.", TextToSpeech.QUEUE_FLUSH, null, "nav")
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ announceNext() }, 1000)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isRouteBuilt) {
            FloatingActionButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).zIndex(10f),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "На базу")
            }
        }
        if (isGridEditMode) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp)
                    .zIndex(10f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Кисть:", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    FilterChip(selected = brushRadiusState.value == 433.0, onClick = { brushRadiusState.value = 433.0 }, label = { Text("1") })
                    FilterChip(selected = brushRadiusState.value == 1200.0, onClick = { brushRadiusState.value = 1200.0 }, label = { Text("7") })
                    FilterChip(selected = brushRadiusState.value == 2000.0, onClick = { brushRadiusState.value = 2000.0 }, label = { Text("19") })
                }
            }
        }
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(14.0)

                    val myLocProvider = GpsMyLocationProvider(ctx)
                    val myLocOverlay = MyLocationNewOverlay(myLocProvider, this)
                    myLocOverlay.enableMyLocation()
                    myLocOverlay.enableFollowLocation()
                    overlays.add(myLocOverlay)

                    var isPainting = false
                    var paintTargetState = false
                    var lastPaintGeo: GeoPoint? = null

                    val paintOverlay = object : org.osmdroid.views.overlay.Overlay() {
                        override fun onTouchEvent(event: android.view.MotionEvent, mapView: MapView): Boolean {
                            if (!isGridEditMode) return super.onTouchEvent(event, mapView)

                            when (event.action) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    isPainting = false
                                    lastPaintGeo = null
                                }
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (isPainting) {
                                        val proj = mapView.projection
                                        val geo = proj.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint

                                        if (lastPaintGeo == null || lastPaintGeo!!.distanceToAsDouble(geo) > 100.0) {
                                            lastPaintGeo = geo
                                            val currentBrush = brushRadiusState.value

                                            var changed = false
                                            val updated = citySectors.map { sector ->
                                                if (sector.center.distanceToAsDouble(geo) <= currentBrush && sector.isActive != paintTargetState) {
                                                    changed = true
                                                    sector.copy(isActive = paintTargetState)
                                                } else {
                                                    sector
                                                }
                                            }
                                            if (changed) citySectors = updated
                                        }
                                        return true
                                    }
                                }
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                    if (isPainting) {
                                        isPainting = false
                                        lastPaintGeo = null
                                        saveSectors(context, citySectors)
                                        return true
                                    }
                                }
                            }
                            return super.onTouchEvent(event, mapView)
                        }
                    }
                    overlays.add(paintOverlay)

                    val mapEventsReceiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (p != null && isGridEditMode) {
                                val clickedSector = citySectors.find { it.center.distanceToAsDouble(p) < 433.0 }
                                if (clickedSector != null) {
                                    post {
                                        val updated = citySectors.map { if (it.id == clickedSector.id) it.copy(isActive = !it.isActive) else it }
                                        citySectors = updated
                                        saveSectors(context, updated)
                                    }
                                }
                            }
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null && isGridEditMode) {
                                val centerSector = citySectors.find { it.center.distanceToAsDouble(p) < 433.0 }
                                if (centerSector != null) {
                                    isPainting = true
                                    paintTargetState = !centerSector.isActive
                                    lastPaintGeo = p
                                    val currentBrush = brushRadiusState.value

                                    post {
                                        val updated = citySectors.map {
                                            if (it.center.distanceToAsDouble(p) <= currentBrush) it.copy(isActive = paintTargetState) else it
                                        }
                                        citySectors = updated
                                    }

                                    try {
                                        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                        } else {
                                            vibrator.vibrate(50)
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                            return true
                        }
                    }
                    overlays.add(MapEventsOverlay(mapEventsReceiver))

                    myLocOverlay.runOnFirstFix {
                        post {
                            userPosition = myLocOverlay.myLocation
                            controller.animateTo(userPosition)
                        }
                    }

                    myLocProvider.startLocationProvider(object : IMyLocationConsumer {
                        override fun onLocationChanged(loc: android.location.Location?, source: IMyLocationProvider?) {
                            if (loc != null) {
                                post {
                                    val currentGeo = GeoPoint(loc.latitude, loc.longitude)
                                    userPosition = currentGeo
                                    if (loc.hasBearing()) currentBearing = loc.bearing

                                    if (isRouteBuilt) {
                                        if (previousLocation != null) {
                                            distanceTraveledMeters += previousLocation!!.distanceTo(loc)
                                            saveTotalDistance(context, previousLocation!!.distanceTo(loc).toDouble())
                                            val distStep = previousLocation!!.distanceTo(loc).toDouble()
                                            distanceSinceLastEventCheck += distStep

                                            // --- ЛОГИКА ДОЖДЯ И ТВАРЕЙ ---
                                            val now = System.currentTimeMillis()
                                            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0

                                            val currentTimefall = timefallZones.find { currentGeo.distanceToAsDouble(it.center) <= it.radius }
                                            if (currentTimefall != null) {
                                                if (!inTimefall) {
                                                    inTimefall = true
                                                    tts.speak("Внимание. Вы входите в зону темпорального дождя. Контейнеры получают урон.", TextToSpeech.QUEUE_ADD, null, "timefall")
                                                }

                                                // Урон от дождя: 1% каждые 10 секунд
                                                if (now - lastTimefallDamageTime > 10000) {
                                                    lastTimefallDamageTime = now
                                                    bikeCargos.forEach { cargo ->
                                                        val newHealth = (cargo.health - 1.0).coerceAtLeast(0.0)
                                                        dbHelper.updateCargoHealth(cargo.id, newHealth)
                                                    }
                                                    refreshBikeCargos()
                                                }

                                                // Проверка на Тварей внутри дождя
                                                val currentBt = currentTimefall.btZones.find { currentGeo.distanceToAsDouble(it.center) <= it.radius }
                                                if (currentBt != null) {
                                                    if (!inBtZone) {
                                                        inBtZone = true
                                                        tts.speak("Обнаружено присутствие Тварей. Снизьте скорость до 5 километров в час.", TextToSpeech.QUEUE_ADD, null, "bt_warn")
                                                    }

                                                    // Урон от Тварей: 10% от остатка, если скорость > 5 км/ч
                                                    if (speedKmh > 5.0) {
                                                        if (now - lastBtAttackTime > 15000) { // Кулдаун атаки 15 сек
                                                            lastBtAttackTime = now
                                                            tts.speak("Твари заметили вас! Груз поврежден.", TextToSpeech.QUEUE_ADD, null, "bt_attack")
                                                            bikeCargos.forEach { cargo ->
                                                                val newHealth = (cargo.health * 0.9).coerceAtLeast(0.0)
                                                                dbHelper.updateCargoHealth(cargo.id, newHealth)
                                                            }
                                                            refreshBikeCargos()
                                                        }
                                                    }
                                                } else {
                                                    if (inBtZone) {
                                                        inBtZone = false
                                                        tts.speak("Вы покинули зону Тварей.", TextToSpeech.QUEUE_ADD, null, "bt_leave")
                                                    }
                                                }
                                            } else {
                                                if (inTimefall) {
                                                    inTimefall = false
                                                    inBtZone = false
                                                    tts.speak("Темпоральный дождь закончился.", TextToSpeech.QUEUE_ADD, null, "timefall_leave")
                                                }
                                            }

                                            // --- СЛУЧАЙНЫЕ СОБЫТИЯ (SOS) ---
                                            if (distanceSinceLastEventCheck >= 1000.0 && pendingSideQuestLocation == null) {
                                                distanceSinceLastEventCheck = 0.0
                                                val roll = Random.nextInt(1, 101)

                                                if (roll <= currentEventChance) {
                                                    currentEventChance = 10
                                                    pendingSideQuestLocation = getPointAtAngle(currentGeo, Random.nextDouble(1000.0, 3000.0), Random.nextDouble(0.0, 360.0))
                                                    showSideQuestDialog = true
                                                    tts.speak("Внимание. Обнаружен неизвестный сигнал. Проверьте терминал.", TextToSpeech.QUEUE_ADD, null, "quest")
                                                } else {
                                                    currentEventChance += 1
                                                }
                                            }

                                            var minDistanceToLine = Double.MAX_VALUE
                                            if (routePoints.size > 1) {
                                                for (i in 0 until routePoints.size - 1) {
                                                    val d = getDistanceToSegment(currentGeo, routePoints[i], routePoints[i+1])
                                                    if (d < minDistanceToLine) minDistanceToLine = d
                                                }
                                            }

                                            if (loc.accuracy < 50f && minDistanceToLine > 150.0) {
                                                val nowTime = System.currentTimeMillis()
                                                if (nowTime - lastOffRouteWarningTime > 60000) {
                                                    tts.speak("Внимание! Вы отклонились от маршрута.", TextToSpeech.QUEUE_FLUSH, null, "nav")
                                                    lastOffRouteWarningTime = nowTime
                                                }
                                            }
                                            val nextStep = navSteps.firstOrNull { !it.announcedNow }
                                            if (nextStep != null) {
                                                val distToTurn = currentGeo.distanceToAsDouble(nextStep.location)
                                                val stepIndex = navSteps.indexOf(nextStep)
                                                val updatedList = navSteps.toMutableList()
                                                var changed = false

                                                if (distToTurn in 300.0..600.0 && !nextStep.announced500) {
                                                    val rounded = (distToTurn / 50).roundToInt() * 50
                                                    tts.speak("Через $rounded метров ${nextStep.instruction}", TextToSpeech.QUEUE_ADD, null, "nav")
                                                    updatedList[stepIndex] = nextStep.copy(announced500 = true)
                                                    changed = true
                                                }
                                                else if (distToTurn in 50.0..150.0 && !nextStep.announced100) {
                                                    val rounded = (distToTurn / 10).roundToInt() * 10
                                                    tts.speak("Через $rounded метров ${nextStep.instruction}", TextToSpeech.QUEUE_ADD, null, "nav")
                                                    updatedList[stepIndex] = nextStep.copy(announced500 = true, announced100 = true)
                                                    changed = true
                                                }
                                                else if (distToTurn < 25.0 && !nextStep.announcedNow) {
                                                    tts.speak(nextStep.instruction, TextToSpeech.QUEUE_ADD, null, "nav")
                                                    updatedList[stepIndex] = nextStep.copy(announced500 = true, announced100 = true, announcedNow = true)
                                                    changed = true
                                                }

                                                if (distToTurn > 100.0 && nextStep.announced100 && !nextStep.announcedNow) {
                                                    updatedList[stepIndex] = nextStep.copy(announcedNow = true)
                                                    changed = true
                                                }

                                                if (changed) navSteps = updatedList
                                            }
                                        }
                                        previousLocation = loc
                                        var cargoDelivered = false

                                        if (baseLocation != null && currentGeo.distanceToAsDouble(baseLocation!!) <= 50.0) {
                                            val cargosForBase = bikeCargos.filter { it.location.distanceToAsDouble(baseLocation!!) < 10.0 }
                                            if (cargosForBase.isNotEmpty()) {
                                                var earnedXp = 0
                                                var earnedMoney = 0

                                                cargosForBase.forEach {
                                                    dbHelper.updateCargoStatus(it.id, "DELIVERED")
                                                    val healthMult = it.health / 100.0
                                                    val finalXp = (it.xpReward * healthMult).roundToInt()
                                                    val finalMoney = (it.moneyReward * healthMult).roundToInt()

                                                    playerXp += finalXp
                                                    playerMoney += finalMoney
                                                    earnedXp += finalXp
                                                    earnedMoney += finalMoney
                                                }

                                                savePlayerMoney(context, playerMoney)
                                                tts.speak("Возврат на базу. Сдано попутных грузов: ${cargosForBase.size}. Заработано $earnedMoney кредитов.", TextToSpeech.QUEUE_ADD, null, "nav")
                                                android.widget.Toast.makeText(context, "База: Сдано грузов (${cargosForBase.size} шт)\n+$earnedXp XP\n+$earnedMoney 💵", android.widget.Toast.LENGTH_LONG).show()

                                                cargoDelivered = true
                                                addTotalDeliveries(context, cargosForBase.size)
                                            }
                                        }

                                        bikeCargos.forEach { cargo ->
                                            if (currentGeo.distanceToAsDouble(cargo.location) <= 25.0 && cargo.location.distanceToAsDouble(baseLocation ?: GeoPoint(0.0,0.0)) > 10.0) {

                                                dbHelper.updateCargoStatus(cargo.id, "DELIVERED")

                                                val healthMult = cargo.health / 100.0
                                                val finalXp = (cargo.xpReward * healthMult).roundToInt()
                                                val finalMoney = (cargo.moneyReward * healthMult).roundToInt()

                                                playerXp += finalXp
                                                playerMoney += finalMoney
                                                savePlayerMoney(context, playerMoney)

                                                val conditionText = "Состояние: ${cargo.health.toInt()} процентов."
                                                tts.speak("Груз доставлен. $conditionText Получено $finalXp опыта и $finalMoney кредитов.", TextToSpeech.QUEUE_ADD, null, "nav")

                                                cargoDelivered = true
                                                addTotalDeliveries(context, 1)

                                                val currentWeight = dbHelper.getBikeWeight()
                                                val template = getRandomCargoByChance(cargoTemplates)
                                                val maxWeight = getCurrentMaxWeight(context)

                                                if (baseLocation != null && currentWeight + template.weightKg <= maxWeight) {
                                                    val returnCargo = CargoItem(
                                                        id = 0, name = template.name, description = template.description,
                                                        weightKg = template.weightKg, isFragile = template.isFragile, location = baseLocation!!,
                                                        xpReward = template.baseXp + Random.nextInt(50, 150),
                                                        moneyReward = template.baseMoney + Random.nextInt(50, 200),
                                                        status = CargoStatus.PENDING, health = 100.0
                                                    )
                                                    dbHelper.addCargoToBike(returnCargo)
                                                    tts.speak("Взят попутный груз до базы: ${template.name}. Вес: ${template.weightKg} килограмм.", TextToSpeech.QUEUE_ADD, null, "nav")
                                                }
                                            }
                                        }

                                        if (cargoDelivered) {
                                            savePlayerXp(context, playerXp)
                                            updateNotification(playerXp)
                                            refreshBikeCargos()
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ announceNext() }, 1000)
                                        }
                                    }
                                }
                            }
                        }
                    })
                }
            },
            update = { mapView ->
                if (isFollowMode && userPosition != null) {
                    mapView.controller.animateTo(userPosition)
                    mapView.mapOrientation = -currentBearing
                } else if (!isFollowMode) {
                    mapView.mapOrientation = 0f
                }

                if (shouldInitCamera) {
                    shouldInitCamera = false
                    mapView.controller.setZoom(18.0)
                    if (userPosition != null) mapView.controller.animateTo(userPosition)
                }

                if (userPosition != null && !hasCenteredOnUser) {
                    hasCenteredOnUser = true
                    mapView.controller.setZoom(16.0)
                    mapView.controller.animateTo(userPosition)
                }

                mapView.overlays.removeAll { it is Marker || it is Polyline || it is Polygon }

                // --- ОТРИСОВКА ДОЖДЯ И ТВАРЕЙ ---
                timefallZones.forEach { tf ->
                    val tfCircle = Polygon(mapView).apply {
                        points = Polygon.pointsAsCircle(tf.center, tf.radius)
                        fillPaint.color = android.graphics.Color.parseColor("#33424242") // Полупрозрачный темно-серый
                        outlinePaint.color = android.graphics.Color.parseColor("#88424242")
                        outlinePaint.strokeWidth = 2f
                    }
                    mapView.overlays.add(tfCircle)

                    tf.btZones.forEach { bt ->
                        val btCircle = Polygon(mapView).apply {
                            points = Polygon.pointsAsCircle(bt.center, bt.radius)
                            fillPaint.color = android.graphics.Color.parseColor("#44000000") // Полупрозрачный черный
                            outlinePaint.color = android.graphics.Color.parseColor("#88000000")
                            outlinePaint.strokeWidth = 2f
                        }
                        mapView.overlays.add(btCircle)
                    }
                }

                if (baseLocation != null) {
                    val baseCircle = Polygon(mapView).apply {
                        points = Polygon.pointsAsCircle(baseLocation, 50.0)
                        fillPaint.color = android.graphics.Color.parseColor("#440088FF")
                        outlinePaint.color = android.graphics.Color.parseColor("#0088FF")
                        outlinePaint.strokeWidth = 5f
                    }
                    mapView.overlays.add(baseCircle)
                }

                if (isGridEditMode || !isRouteBuilt) {
                    citySectors.forEach { sector ->
                        val hexPolygon = Polygon(mapView).apply {
                            val pts = mutableListOf<GeoPoint>()
                            for (i in 0..5) {
                                pts.add(getPointAtAngle(sector.center, 500.0, 60.0 * i))
                            }
                            points = pts

                            if (sector.isActive) {
                                val alpha = maxOf(10, 68 - (sector.visitCount * 10))
                                val hexAlpha = alpha.toString(16).padStart(2, '0')
                                fillPaint.color = android.graphics.Color.parseColor("#${hexAlpha}00FF00")
                            } else {
                                fillPaint.color = android.graphics.Color.parseColor("#33FF0000")
                            }
                            outlinePaint.color = android.graphics.Color.parseColor("#66000000")
                            outlinePaint.strokeWidth = 3f
                        }
                        mapView.overlays.add(hexPolygon)

                        if (sector.isActive) {
                            val textMarker = Marker(mapView).apply {
                                position = sector.center
                                icon = createCustomMarker(context, sector.visitCount, android.graphics.Color.parseColor("#00AA00"))
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                infoWindow = null

                                setOnMarkerClickListener { _, _ ->
                                    if (isGridEditMode) {
                                        val updated = citySectors.map { if (it.id == sector.id) it.copy(isActive = !it.isActive) else it }
                                        citySectors = updated
                                        saveSectors(context, updated)
                                    }
                                    true
                                }
                            }
                            mapView.overlays.add(textMarker)
                        }
                    }
                }

                if (routePoints.isNotEmpty()) {
                    var closestUserIndex = 0
                    if (userPosition != null) {
                        var minDt = Double.MAX_VALUE
                        for (i in routePoints.indices) {
                            val d = userPosition!!.distanceToAsDouble(routePoints[i])
                            if (d < minDt) {
                                minDt = d
                                closestUserIndex = i
                            }
                        }
                    }
                    val nextCargo = cargoList.firstOrNull { it.status == CargoStatus.PENDING }
                    var nextCargoIndex = routePoints.size - 1
                    if (nextCargo != null) {
                        var minDtCargo = Double.MAX_VALUE
                        for (i in closestUserIndex until routePoints.size) {
                            val d = routePoints[i].distanceToAsDouble(nextCargo.location)
                            if (d < minDtCargo) {
                                minDtCargo = d
                                nextCargoIndex = i
                            }
                        }
                    }
                    if (closestUserIndex > 0) {
                        val traveledLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(0, closestUserIndex + 1))
                            outlinePaint.color = android.graphics.Color.parseColor("#888888")
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(traveledLine)
                    }
                    if (nextCargoIndex < routePoints.size - 1) {
                        val futureLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(nextCargoIndex, routePoints.size))
                            outlinePaint.color = android.graphics.Color.parseColor("#FFA500")
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(futureLine)
                    }

                    if (closestUserIndex < nextCargoIndex) {
                        val currentLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(closestUserIndex, nextCargoIndex + 1))
                            outlinePaint.color = android.graphics.Color.parseColor("#00FF00")
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(currentLine)
                    }
                    if (!hasZoomedToRoute) {
                        hasZoomedToRoute = true
                        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(routePoints), true, 150)
                    }
                }

                val nextCargoId = cargoList.firstOrNull { it.status == CargoStatus.PENDING }?.id
                cargoList.forEach { cargo ->
                    if (cargo.status == CargoStatus.PENDING) {
                        val isNext = (cargo.id == nextCargoId)
                        val mainColorStr = if (isNext) "#00FF00" else "#FFA500"
                        val mainColor = android.graphics.Color.parseColor(mainColorStr)
                        val circleColor = android.graphics.Color.parseColor(if (isNext) "#4400FF00" else "#44FFA500")
                        val circlePolygon = Polygon(mapView).apply {
                            points = Polygon.pointsAsCircle(cargo.location, 25.0)
                            fillPaint.color = circleColor
                            outlinePaint.color = mainColor
                            outlinePaint.strokeWidth = 3f
                        }
                        mapView.overlays.add(circlePolygon)
                        val marker = Marker(mapView).apply {
                            position = cargo.location
                            title = cargo.name
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = createCustomMarker(context, cargo.id, mainColor)
                            setOnMarkerClickListener { _, _ ->
                                selectedCargo = cargo
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                if (userPosition != null) {
                    val userArrowMarker = Marker(mapView).apply {
                        position = userPosition
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = createUserArrowMarker(context)
                        setFlat(false)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> true }
                    }
                    mapView.overlays.add(userArrowMarker)
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isRouteBuilt) {
            FloatingActionButton(
                onClick = {
                    isFollowMode = !isFollowMode
                    if (isFollowMode) shouldInitCamera = true
                },
                containerColor = if (isFollowMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, bottom = 190.dp)
            ) { Icon(Icons.Filled.Navigation, contentDescription = "Режим навигации") }
        }

        if (userPosition != null && !isRouteBuilt && !isGridEditMode) {
            Card(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth(0.9f)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text("Навигационный терминал", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                        IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.align(Alignment.CenterEnd).offset(x = 8.dp, y = (-8).dp)) {
                            Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (bikeCargos.isEmpty()) {
                        Text("Велосипед пуст!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                        Text("Зайдите на склад и загрузите посылки.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Грузов на багажнике: ${bikeCargos.size}", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(12.dp))

                        if (isLoading) {
                            CircularProgressIndicator()
                        } else {
                            Button(
                                onClick = {
                                    val start = userPosition ?: return@Button
                                    isLoading = true
                                    coroutineScope.launch {
                                        val sortedCargos = sortCargosByNearest(start, bikeCargos)

                                        val waypoints = mutableListOf<GeoPoint>()
                                        waypoints.add(start)
                                        waypoints.addAll(sortedCargos.map { it.location })
                                        waypoints.add(start)

                                        val res = fetchOSRMRoute(waypoints)

                                        if (res != null) {
                                            if (res.distanceMeters == -429.0) {
                                                android.widget.Toast.makeText(context, "Лимит сервера. Ждите 1 мин.", android.widget.Toast.LENGTH_LONG).show()
                                            } else {
                                                routePoints = res.path
                                                navSteps = res.steps
                                                totalDistanceMeters = res.distanceMeters
                                                distanceTraveledMeters = 0.0
                                                previousLocation = null

                                                cargoList = sortedCargos

                                                // --- ГЕНЕРАЦИЯ ДОЖДЯ И ТВАРЕЙ НА МАРШРУТЕ ---
                                                val newTimefalls = mutableListOf<TimefallZone>()
                                                val numTimefalls = (res.distanceMeters / 2500.0).toInt().coerceIn(1, 4)
                                                val step = res.path.size / (numTimefalls + 1)
                                                for (i in 1..numTimefalls) {
                                                    val idx = (i * step).coerceIn(0, res.path.size - 1)
                                                    val center = res.path[idx]
                                                    val btZones = List(Random.nextInt(1, 4)) {
                                                        BTZone(getPointAtAngle(center, Random.nextDouble(0.0, 200.0), Random.nextDouble(0.0, 360.0)))
                                                    }
                                                    newTimefalls.add(TimefallZone(center, 250.0, btZones))
                                                }
                                                timefallZones = newTimefalls

                                                isFollowMode = true
                                                shouldInitCamera = true
                                                hasZoomedToRoute = true
                                                isRouteBuilt = true

                                                val firstPt = routePoints.firstOrNull()
                                                if (firstPt != null) currentBearing = getBearingBetween(start, firstPt)

                                                updateNotification(playerXp)

                                                val tKm = ((res.distanceMeters / 1000.0) * 10).roundToInt() / 10.0
                                                val firstCargo = sortedCargos.firstOrNull { it.status == CargoStatus.PENDING }
                                                val cargoAnnouncement = if (firstCargo != null) {
                                                    val fragileWarning = if (firstCargo.isFragile) "Груз хрупкий, требует внимания." else ""
                                                    " Доставка первого груза: ${firstCargo.name}. ${firstCargo.description}. $fragileWarning"
                                                } else " Следуйте к первой цели."

                                                tts.speak("Маршрут на $tKm километров построен.$cargoAnnouncement", TextToSpeech.QUEUE_FLUSH, null, "nav")
                                            }
                                        } else {
                                            android.widget.Toast.makeText(context, "Ошибка построения маршрута", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        isLoading = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Проложить маршрут") }
                        }
                    }
                }
            }
        }

        if (isRouteBuilt) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(0.9f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val currentLevel = levelsDb.lastOrNull { playerXp >= it.requiredXp } ?: levelsDb.first()
                    val nextLevel = levelsDb.firstOrNull { it.level == currentLevel.level + 1 }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "⭐ ${currentLevel.title}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$playerXp XP",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    if (nextLevel != null) {
                        val progress = (playerXp - currentLevel.requiredXp).toFloat() / (nextLevel.requiredXp - currentLevel.requiredXp).toFloat()
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                        Text("До следующего уровня: ${nextLevel.requiredXp - playerXp} XP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Максимальный уровень достигнут!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    val totalKm = ((totalDistanceMeters / 1000.0) * 100).roundToInt() / 100.0
                    val traveledKm = ((distanceTraveledMeters / 1000.0) * 100).roundToInt() / 100.0
                    Text("🏁 Маршрут: $traveledKm / $totalKm км")

                    val pendingCount = cargoList.count { it.status == CargoStatus.PENDING }
                    Text("📦 Грузов осталось: $pendingCount / ${cargoList.size}", color = if (pendingCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (!isRouteBuilt) {
            FloatingActionButton(
                onClick = {
                    if (citySectors.isEmpty() && userPosition != null) {
                        val newGrid = generateCityGrid(userPosition!!)
                        citySectors = newGrid
                        saveSectors(context, newGrid)
                    }
                    isGridEditMode = !isGridEditMode

                    if (isGridEditMode) {
                        android.widget.Toast.makeText(context, "Клик - 1 гекс\nУдержание - закрасить область (2км)", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                containerColor = if (isGridEditMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) { Icon(if (isGridEditMode) Icons.Filled.List else Icons.Filled.Settings, contentDescription = "Сетка") }
        }
    }

    if (showListDialog) {
        AlertDialog(
            onDismissRequest = { showListDialog = false },
            title = { Text("Список грузов") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(cargoList) { cargo ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { showListDialog = false; selectedCargo = cargo },
                            colors = CardDefaults.cardColors(containerColor = when (cargo.status) {
                                CargoStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                                CargoStatus.COLLECTED -> androidx.compose.ui.graphics.Color(0xFFCCFFCC)
                                CargoStatus.CANCELED -> androidx.compose.ui.graphics.Color(0xFFFFCCCC)
                            })
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(cargo.name, style = MaterialTheme.typography.titleSmall)
                                Text("Статус: ${cargo.status} | Состояние: ${cargo.health.toInt()}%")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showListDialog = false }) { Text("Закрыть") } }
        )
    }

    if (selectedCargo != null) {
        val cargo = selectedCargo!!
        AlertDialog(
            onDismissRequest = { selectedCargo = null },
            title = { Text(cargo.name) },
            text = {
                Column {
                    Text("Описание: ${cargo.description}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Вес: ${cargo.weightKg} кг")
                    Text("Хрупкий: ${if (cargo.isFragile) "Да" else "Нет"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Состояние: ${cargo.health.toInt()}%", color = if (cargo.health < 50) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.primary)
                    Text("Статус: ${cargo.status}")
                }
            },
            confirmButton = {
                if (cargo.status == CargoStatus.PENDING) {
                    Button(onClick = { processCargo(cargo.id, CargoStatus.COLLECTED); selectedCargo = null }) { Text("Собрать") }
                }
            },
            dismissButton = {
                if (cargo.status == CargoStatus.PENDING) {
                    OutlinedButton(onClick = { processCargo(cargo.id, CargoStatus.CANCELED); selectedCargo = null }) { Text("Отменить (Скип)") }
                } else {
                    TextButton(onClick = { selectedCargo = null }) { Text("Закрыть") }
                }
            }
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Настройки терминала") },
            text = {
                val scrollState = rememberScrollState()
                Column(modifier = Modifier.verticalScroll(scrollState)) {

                    Text("Узел связи (База)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (userPosition != null) {
                                baseLocation = userPosition
                                saveBaseLocation(context, userPosition!!)
                                android.widget.Toast.makeText(context, "Домашний терминал установлен!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF007AFF))
                    ) { Text("Установить Базу на моем месте") }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val activeSectors = citySectors.filter { it.isActive }
                            if (activeSectors.isNotEmpty()) {
                                for (i in 1..10) {
                                    val template = getRandomCargoByChance(cargoTemplates)
                                    val randomSector = activeSectors.random()
                                    val xpReward = template.baseXp + (if (template.isFragile) 50 else 0) + Random.nextInt(10, 50)
                                    val moneyReward = template.baseMoney + (if (template.isFragile) 100 else 0) + Random.nextInt(20, 100)

                                    val newCargo = CargoItem(0, template.name, template.description, template.weightKg, template.isFragile, randomSector.center, xpReward, moneyReward, CargoStatus.PENDING, 100.0)
                                    dbHelper.addCargoToWarehouse(newCargo)
                                }
                                android.widget.Toast.makeText(context, "10 грузов доставлено на склад!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Сначала создайте сетку!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                    ) { Text("Завезти 10 грузов на склад") }

                    Text("Голосовой ассистент", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(onClick = {
                        try {
                            val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
                            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        } catch (e: Exception) { android.widget.Toast.makeText(context, "Не удалось открыть настройки", android.widget.Toast.LENGTH_SHORT).show() }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("Выбрать движок Samsung в системе") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        try {
                            val pm = context.packageManager
                            val intent = android.content.Intent("android.intent.action.TTS_SERVICE")
                            val resolveInfos = pm.queryIntentServices(intent, 0)
                            val allEngines = resolveInfos.joinToString("\n") { it.serviceInfo.packageName }
                            ttsDiagText = "Текущий:\n${tts.defaultEngine}\n\nУстановлены:\n$allEngines"
                        } catch (e: Exception) { ttsDiagText = "Ошибка сканирования" }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Диагностика движков TTS") }

                    if (availableVoices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ExposedDropdownMenuBox(expanded = isVoiceDropdownExpanded, onExpandedChange = { isVoiceDropdownExpanded = !isVoiceDropdownExpanded }) {
                            OutlinedTextField(value = selectedVoiceName, onValueChange = {}, readOnly = true, label = { Text("Выберите голос") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVoiceDropdownExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                            ExposedDropdownMenu(expanded = isVoiceDropdownExpanded, onDismissRequest = { isVoiceDropdownExpanded = false }) {
                                availableVoices.forEachIndexed { index, voice ->
                                    DropdownMenuItem(text = { Text("Голос ${index + 1} (${voice.name})") }, onClick = {
                                        tts.voice = voice; selectedVoiceName = voice.name; isVoiceDropdownExpanded = false
                                        context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putString("saved_voice", voice.name).apply()
                                        tts.speak("Голос установлен. Проверка связи.", TextToSpeech.QUEUE_FLUSH, null, "test")
                                    })
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Тон (Роботизация): ${((ttsPitch * 10).roundToInt() / 10f)}", style = MaterialTheme.typography.bodyMedium)
                        Slider(value = ttsPitch, onValueChange = { ttsPitch = it }, onValueChangeFinished = {
                            tts.setPitch(ttsPitch)
                            context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putFloat("tts_pitch", ttsPitch).apply()
                            tts.speak("Тон системы изменен", TextToSpeech.QUEUE_FLUSH, null, "test")
                        }, valueRange = 0.1f..2.0f)
                        Text("Скорость речи: ${((ttsRate * 10).roundToInt() / 10f)}", style = MaterialTheme.typography.bodyMedium)
                        Slider(value = ttsRate, onValueChange = { ttsRate = it }, onValueChangeFinished = {
                            tts.setSpeechRate(ttsRate)
                            context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putFloat("tts_rate", ttsRate).apply()
                            tts.speak("Скорость системы изменена", TextToSpeech.QUEUE_FLUSH, null, "test")
                        }, valueRange = 0.1f..2.0f)
                    } else {
                        Text("Доступные голоса не найдены", color = MaterialTheme.colorScheme.error)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text("Система", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(onClick = {
                        citySectors = emptyList()
                        saveSectors(context, emptyList())
                        android.widget.Toast.makeText(context, "Сетка сброшена", android.widget.Toast.LENGTH_SHORT).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text("Сбросить Хиральную сеть") }
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(onClick = {
                        visitedPoints = emptyList()
                        saveVisitedPoints(context, emptyList())
                        android.widget.Toast.makeText(context, "История очищена", android.widget.Toast.LENGTH_SHORT).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text("Сбросить историю мест (500м)") }
                    Button(
                        onClick = {
                            dbHelper.clearAllPendingCargo()
                            android.widget.Toast.makeText(context, "Все грузы утилизированы", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Утилизировать все грузы (Склад и Велик)") }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Закрыть") } }
        )
    }

    if (showSideQuestDialog && pendingSideQuestLocation != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("⚠️ Неизвестный сигнал") },
            text = { Text("Датчики засекли утерянный груз в радиусе 3 км. Отклониться от курса и забрать?") },
            confirmButton = {
                Button(onClick = {
                    showSideQuestDialog = false
                    isLoading = true

                    coroutineScope.launch {
                        val currentPos = userPosition ?: return@launch
                        val homePos = routePoints.lastOrNull() ?: currentPos

                        val pendingCargos = cargoList.filter { it.status == CargoStatus.PENDING }

                        val waypoints = mutableListOf<GeoPoint>()
                        waypoints.add(currentPos)
                        waypoints.add(pendingSideQuestLocation!!)
                        waypoints.addAll(pendingCargos.map { it.location })
                        if (waypoints.last() != homePos) waypoints.add(homePos)

                        val newRes = fetchOSRMRoute(waypoints)

                        if (newRes != null) {
                            val newCargoId = (cargoList.maxOfOrNull { it.id } ?: 0) + 1
                            val sideQuestCargo = CargoItem(
                                id = newCargoId,
                                name = "Утерянный груз (SOS)",
                                description = "Случайная находка. Содержимое неизвестно.",
                                weightKg = Random.nextDouble(2.0, 15.0).roundToInt().toDouble(),
                                isFragile = Random.nextBoolean(),
                                location = pendingSideQuestLocation!!,
                                xpReward = Random.nextInt(300, 800),
                                moneyReward = Random.nextInt(500, 1500),
                                status = CargoStatus.PENDING,
                                health = 100.0
                            )

                            val collected = cargoList.filter { it.status != CargoStatus.PENDING }
                            cargoList = collected + listOf(sideQuestCargo) + pendingCargos

                            routePoints = newRes.path
                            navSteps = newRes.steps

                            totalDistanceMeters = distanceTraveledMeters + newRes.distanceMeters

                            tts.speak("Маршрут перестроен. Следуйте к новой цели.", TextToSpeech.QUEUE_FLUSH, null, "nav")
                        } else {
                            android.widget.Toast.makeText(context, "Не удалось проложить маршрут к сигналу", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        pendingSideQuestLocation = null
                        isLoading = false
                    }
                }) { Text("Принять") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showSideQuestDialog = false
                    pendingSideQuestLocation = null
                    tts.speak("Сигнал проигнорирован. Возвращаюсь к текущему маршруту.", TextToSpeech.QUEUE_FLUSH, null, "nav")
                }) { Text("Игнорировать") }
            }
        )
    }
    if (ttsDiagText.isNotEmpty()) {
        AlertDialog(onDismissRequest = { ttsDiagText = "" }, title = { Text("Вскрытие показало:") }, text = { Text(ttsDiagText) }, confirmButton = { Button(onClick = { ttsDiagText = "" }) { Text("Закрыть") } })
    }
}

fun sortCargosByNearest(start: GeoPoint, cargos: List<CargoItem>): List<CargoItem> {
    val sorted = mutableListOf<CargoItem>()
    val remaining = cargos.toMutableList()
    var currentPos = start

    while (remaining.isNotEmpty()) {
        val nearest = remaining.minByOrNull { it.location.distanceToAsDouble(currentPos) }!!
        sorted.add(nearest)
        remaining.remove(nearest)
        currentPos = nearest.location
    }
    return sorted
}

fun getRandomCargoByChance(templates: List<CargoTemplate>): CargoTemplate {
    if (templates.isEmpty()) return CargoTemplate("Пусто", "Пусто", 0.0, false, 100, "Материалы", 50, 50)
    val totalChance = templates.sumOf { it.spawnChance }
    var randomVal = Random.nextInt(0, totalChance)
    for (t in templates) {
        randomVal -= t.spawnChance
        if (randomVal < 0) return t
    }
    return templates.last()
}

enum class ScreenState { HUB, MAP, WAREHOUSE, CONTRACTS, STATS, HEATMAP }

fun calculateEstimatedRoute(start: GeoPoint, cargos: List<CargoItem>): Double {
    if (cargos.isEmpty()) return 0.0
    var dist = 0.0
    var currentPos = start
    val remaining = cargos.toMutableList()

    while (remaining.isNotEmpty()) {
        val nearest = remaining.minByOrNull { it.location.distanceToAsDouble(currentPos) }!!
        dist += currentPos.distanceToAsDouble(nearest.location)
        currentPos = nearest.location
        remaining.remove(nearest)
    }
    dist += currentPos.distanceToAsDouble(start)
    return dist * 1.3
}

fun getRandomPointInHex(center: GeoPoint): GeoPoint {
    val randomAngle = Random.nextDouble(0.0, 360.0)
    val randomDist = Random.nextDouble(0.0, 400.0)
    return getPointAtAngle(center, randomDist, randomAngle)
}

@Composable
fun WarehouseScreen(dbHelper: DatabaseHelper, onClose: () -> Unit) {
    val context = LocalContext.current

    var warehouseItems by remember { mutableStateOf(dbHelper.getCargoByStatus("IN_WAREHOUSE")) }
    var offeredItems by remember { mutableStateOf(dbHelper.getCargoByStatus("OFFERED")) }
    var acceptedItems by remember { mutableStateOf(dbHelper.getCargoByStatus("ACCEPTED")) }
    var bikeItems by remember { mutableStateOf(dbHelper.getCargoByStatus("ON_BIKE")) }

    val currentWeight = bikeItems.sumOf { it.weightKg }
    val maxWeight = getCurrentMaxWeight(context)

    val refreshData = {
        warehouseItems = dbHelper.getCargoByStatus("IN_WAREHOUSE")
        offeredItems = dbHelper.getCargoByStatus("OFFERED")
        acceptedItems = dbHelper.getCargoByStatus("ACCEPTED")
        bikeItems = dbHelper.getCargoByStatus("ON_BIKE")
    }

    data class UiItem(val firstItem: CargoItem, val count: Int, val type: String)
    val unifiedList = mutableListOf<UiItem>()

    bikeItems.groupBy { it.name }.forEach { (_, items) -> unifiedList.add(UiItem(items.first(), items.size, "ON_BIKE")) }
    acceptedItems.groupBy { it.name }.forEach { (_, items) -> unifiedList.add(UiItem(items.first(), items.size, "ACCEPTED")) }
    offeredItems.groupBy { it.name }.forEach { (_, items) -> unifiedList.add(UiItem(items.first(), items.size, "OFFERED")) }
    warehouseItems.groupBy { it.name }.forEach { (_, items) -> unifiedList.add(UiItem(items.first(), items.size, "IN_WAREHOUSE")) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Багажник: $currentWeight / $maxWeight кг", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            }

            val isOverweight = currentWeight > maxWeight * 0.9
            LinearProgressIndicator(
                progress = (currentWeight / maxWeight).toFloat().coerceIn(0f, 1f),
                color = if (isOverweight) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        var tempWeight = currentWeight
                        for (item in acceptedItems) {
                            if (tempWeight + item.weightKg <= maxWeight) {
                                dbHelper.updateCargoStatus(item.id, "ON_BIKE")
                                tempWeight += item.weightKg
                            }
                        }
                        refreshData()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Авто-загрузка", style = MaterialTheme.typography.bodySmall) }

                OutlinedButton(
                    onClick = {
                        bikeItems.forEach { dbHelper.updateCargoStatus(it.id, "IN_WAREHOUSE") }
                        refreshData()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Снять всё", style = MaterialTheme.typography.bodySmall) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (unifiedList.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Склад пуст. Завезите грузы через терминал на карте.", color = androidx.compose.ui.graphics.Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(unifiedList) { uiItem ->
                        val cargo = uiItem.firstItem
                        val count = uiItem.count

                        val (bgColor, statusText, statusColor) = when (uiItem.type) {
                            "ON_BIKE" -> Triple(MaterialTheme.colorScheme.primaryContainer, "🟢 Снаряжен (На велике)", MaterialTheme.colorScheme.primary)
                            "ACCEPTED" -> Triple(androidx.compose.ui.graphics.Color(0xFFE8F5E9), "🟠 Требуется доставка", androidx.compose.ui.graphics.Color(0xFF2E7D32))
                            "OFFERED" -> Triple(MaterialTheme.colorScheme.surfaceVariant, "🟡 Доступно как контракт", androidx.compose.ui.graphics.Color(0xFFF57C00))
                            else -> Triple(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), "⚪ Запас на складе", androidx.compose.ui.graphics.Color.Gray)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    if (uiItem.type == "ON_BIKE") {
                                        dbHelper.updateCargoStatus(cargo.id, "IN_WAREHOUSE")
                                        refreshData()
                                    } else {
                                        if (currentWeight + cargo.weightKg <= maxWeight) {
                                            dbHelper.updateCargoStatus(cargo.id, "ON_BIKE")
                                            refreshData()
                                        } else {
                                            android.widget.Toast.makeText(context, "Перегруз багажника!", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = bgColor)
                        ) {
                            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("${cargo.name} x$count", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text("${cargo.weightKg * count} кг", style = MaterialTheme.typography.titleMedium)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text("Сост: ${cargo.health.toInt()}% | ${cargo.xpReward * count} XP | ${cargo.moneyReward * count} 💵", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf(ScreenState.HUB) }
    val context = LocalContext.current
    val dbHelper = remember { DatabaseHelper(context) }

    BackHandler(enabled = currentScreen != ScreenState.HUB) {
        currentScreen = ScreenState.HUB
    }

    when (currentScreen) {
        ScreenState.HUB -> MainHubScreen(
            onNavigateToMap = { currentScreen = ScreenState.MAP },
            onNavigateToWarehouse = { currentScreen = ScreenState.WAREHOUSE },
            onNavigateToContracts = { currentScreen = ScreenState.CONTRACTS },
            onNavigateToStats = { currentScreen = ScreenState.STATS }
        )
        ScreenState.MAP -> MapScreen(onBack = { currentScreen = ScreenState.HUB })
        ScreenState.WAREHOUSE -> WarehouseScreen(dbHelper = dbHelper, onClose = { currentScreen = ScreenState.HUB })
        ScreenState.CONTRACTS -> ContractsMapScreen(dbHelper = dbHelper, onBack = { currentScreen = ScreenState.HUB })
        ScreenState.STATS -> StatsScreen(onBack = { currentScreen = ScreenState.HUB }, onShowHeatmap = { currentScreen = ScreenState.HEATMAP })
        ScreenState.HEATMAP -> HeatmapScreen(onBack = { currentScreen = ScreenState.STATS })
    }
}

@Composable
fun MainHubScreen(
    onNavigateToMap: () -> Unit,
    onNavigateToWarehouse: () -> Unit,
    onNavigateToContracts: () -> Unit,
    onNavigateToStats: () -> Unit
) {
    val context = LocalContext.current
    val playerXp = loadPlayerXp(context)
    var playerMoney by remember { mutableStateOf(loadPlayerMoney(context)) }

    val levelsDb = remember { loadLevelsDb(context) }
    val currentLevel = levelsDb.lastOrNull { playerXp >= it.requiredXp } ?: levelsDb.first()

    var currentUpgradeLevel by remember { mutableStateOf(loadBikeUpgradeLevel(context)) }
    val upgradesDb = remember { loadUpgradesDb(context) }
    val currentUpgrade = upgradesDb.find { it.level == currentUpgradeLevel } ?: upgradesDb.first()
    val nextUpgrade = upgradesDb.find { it.level == currentUpgradeLevel + 1 }

    var showGarageDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("УЗЕЛ РАСПРЕДЕЛЕНИЯ", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⭐ ${currentLevel.title}", style = MaterialTheme.typography.titleLarge)
                    Text("Опыт: $playerXp XP", style = MaterialTheme.typography.bodyMedium)
                    Text("Баланс: $playerMoney 💵", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Грузоподъемность: ${currentUpgrade.maxWeightKg} кг", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onNavigateToMap, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("Терминал доставки (Карта)", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onNavigateToWarehouse, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("Личное хранилище (Склад)", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(onClick = { showGarageDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("Гараж (Улучшения)", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val db = DatabaseHelper(context)
                    val baseLoc = loadBaseLocation(context)
                    val sectors = loadSectors(context).filter { it.isActive }

                    if (baseLoc != null && sectors.isNotEmpty()) {
                        val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
                        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
                        val lastGenDate = prefs.getString("last_gen_date", "")
                        val currentOffered = db.getCargoByStatus("OFFERED")

                        if (lastGenDate != today || currentOffered.isEmpty()) {
                            currentOffered.forEach { db.updateCargoStatus(it.id, "IN_WAREHOUSE") }
                            val warehouseItems = db.getCargoByStatus("IN_WAREHOUSE")

                            if (warehouseItems.isEmpty()) {
                                android.widget.Toast.makeText(context, "Склад пуст! Завезите грузы через терминал.", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                val itemsToOffer = warehouseItems.shuffled().take(15)
                                itemsToOffer.forEach { db.updateCargoStatus(it.id, "OFFERED") }
                                prefs.edit().putString("last_gen_date", today).apply()
                                android.widget.Toast.makeText(context, "Новые контракты сформированы!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        onNavigateToContracts()
                    } else {
                        android.widget.Toast.makeText(context, "Сначала установите Базу на карте и создайте сетку!", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            ) {
                Text("Контракты (Карта заказов)", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onNavigateToStats, modifier = Modifier.fillMaxWidth()) {
                Text("Личное дело (Статистика)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showGarageDialog) {
        AlertDialog(
            onDismissRequest = { showGarageDialog = false },
            title = { Text("Гараж") },
            text = {
                Column {
                    Text("Текущий транспорт:", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("${currentUpgrade.name} (Макс. вес: ${currentUpgrade.maxWeightKg} кг)")
                    Spacer(modifier = Modifier.height(16.dp))

                    if (nextUpgrade != null) {
                        Text("Доступное улучшение:", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("${nextUpgrade.name} (Макс. вес: ${nextUpgrade.maxWeightKg} кг)")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Стоимость: ${nextUpgrade.costMoney} 💵", color = androidx.compose.ui.graphics.Color(0xFF4CAF50), style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("Транспорт прокачан до максимума!", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
            confirmButton = {
                if (nextUpgrade != null) {
                    Button(
                        onClick = {
                            if (playerMoney >= nextUpgrade.costMoney) {
                                playerMoney -= nextUpgrade.costMoney
                                savePlayerMoney(context, playerMoney)
                                currentUpgradeLevel = nextUpgrade.level
                                saveBikeUpgradeLevel(context, currentUpgradeLevel)
                                android.widget.Toast.makeText(context, "Улучшение куплено!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Недостаточно кредитов!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                    ) { Text("Купить") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showGarageDialog = false }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
fun ContractsMapScreen(dbHelper: DatabaseHelper, onBack: () -> Unit) {
    val context = LocalContext.current
    var userPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedContract by remember { mutableStateOf<CargoItem?>(null) }
    var hasCentered by remember { mutableStateOf(false) }
    var shouldCenterCamera by remember { mutableStateOf(false) }
    val maxWeight = getCurrentMaxWeight(context)
    val baseLocation = loadBaseLocation(context)

    var offeredContracts by remember { mutableStateOf(dbHelper.getCargoByStatus("OFFERED")) }
    var warehouseCargos by remember { mutableStateOf(dbHelper.getCargoByStatus("IN_WAREHOUSE")) }
    var bikeCargos by remember { mutableStateOf(dbHelper.getCargoByStatus("ON_BIKE")) }

    var sessionAcceptedCargos by remember { mutableStateOf<List<CargoItem>>(emptyList()) }

    val bikeWeight = bikeCargos.sumOf { it.weightKg }
    val sessionWeight = sessionAcceptedCargos.sumOf { it.weightKg }
    val totalPlannedWeight = bikeWeight + sessionWeight

    val deliveryCargos = (bikeCargos + sessionAcceptedCargos).filter {
        baseLocation == null || it.location.distanceToAsDouble(baseLocation) > 10.0
    }

    val startPt = userPosition ?: baseLocation ?: GeoPoint(0.0, 0.0)

    var previewRoute by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var actualDistKm by remember { mutableStateOf(0.0) }
    var isRouteLoading by remember { mutableStateOf(false) }

    LaunchedEffect(deliveryCargos, startPt) {
        if (deliveryCargos.isEmpty() || startPt.latitude == 0.0) {
            previewRoute = emptyList()
            actualDistKm = 0.0
            return@LaunchedEffect
        }

        isRouteLoading = true
        val sorted = sortCargosByNearest(startPt, deliveryCargos)
        val waypoints = mutableListOf<GeoPoint>()
        waypoints.add(startPt)
        waypoints.addAll(sorted.map { it.location })
        waypoints.add(startPt)

        val res = fetchOSRMRoute(waypoints)
        if (res != null && res.distanceMeters != -429.0) {
            previewRoute = res.path
            actualDistKm = ((res.distanceMeters / 1000.0) * 10).roundToInt() / 10.0
        }
        isRouteLoading = false
    }

    val estTimeMins = ((actualDistKm / 11.0) * 60.0).roundToInt()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(13.0)

                    val myLocProvider = GpsMyLocationProvider(ctx)
                    val myLocOverlay = MyLocationNewOverlay(myLocProvider, this)
                    myLocOverlay.enableMyLocation()
                    overlays.add(myLocOverlay)

                    myLocProvider.startLocationProvider(object : IMyLocationConsumer {
                        override fun onLocationChanged(loc: android.location.Location?, source: IMyLocationProvider?) {
                            if (loc != null) post { userPosition = GeoPoint(loc.latitude, loc.longitude) }
                        }
                    })
                }
            },
            update = { mapView ->
                if (userPosition != null && !hasCentered) {
                    hasCentered = true
                    mapView.controller.animateTo(userPosition)
                    mapView.controller.setZoom(13.5)
                }

                if (shouldCenterCamera && userPosition != null) {
                    shouldCenterCamera = false
                    mapView.controller.animateTo(userPosition)
                    mapView.controller.setZoom(14.5)
                }

                mapView.overlays.removeAll { it is Marker || it is Polyline }

                if (previewRoute.isNotEmpty()) {
                    val line = Polyline(mapView).apply {
                        setPoints(previewRoute)
                        outlinePaint.color = android.graphics.Color.parseColor("#0088FF")
                        outlinePaint.strokeWidth = 10f
                    }
                    mapView.overlays.add(line)
                }

                offeredContracts.forEach { cargo ->
                    val distKm = if (userPosition != null) {
                        (userPosition!!.distanceToAsDouble(cargo.location) / 100.0).roundToInt() / 10.0
                    } else 0.0

                    val marker = Marker(mapView).apply {
                        position = cargo.location
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = createContractMarker(context, cargo.name, "$distKm км", cargo.xpReward, cargo.moneyReward, android.graphics.Color.parseColor("#FF9800"))
                        setOnMarkerClickListener { _, _ ->
                            selectedContract = cargo
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                deliveryCargos.forEach { cargo ->
                    val marker = Marker(mapView).apply {
                        position = cargo.location
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = createDistanceMarker(context, "Взят", android.graphics.Color.parseColor("#4CAF50"))
                        setOnMarkerClickListener { _, _ -> true }
                    }
                    mapView.overlays.add(marker)
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        Card(
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth(0.95f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
        ) {
            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {

                    Text("Доступно контрактов: ${offeredContracts.size}", style = MaterialTheme.typography.titleMedium)
                    Text("План загрузки: $totalPlannedWeight / $maxWeight кг", color = if (totalPlannedWeight > maxWeight * 0.9) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))

                    if (isRouteLoading) {
                        Text("Прокладка маршрута...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    } else {
                        Text("План маршрута: $actualDistKm км (~$estTimeMins мин)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            }
        }

        FloatingActionButton(
            onClick = { shouldCenterCamera = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Где я")
        }

        FloatingActionButton(
            onClick = {
                val sectors = loadSectors(context).filter { it.isActive }
                if (baseLocation != null && sectors.isNotEmpty()) {
                    val oldOffered = dbHelper.getCargoByStatus("OFFERED")
                    oldOffered.forEach { dbHelper.updateCargoStatus(it.id, "IN_WAREHOUSE") }

                    val warehouseItems = dbHelper.getCargoByStatus("IN_WAREHOUSE")
                    if (warehouseItems.isEmpty()) {
                        android.widget.Toast.makeText(context, "Склад пуст! Завезите грузы через терминал.", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val itemsToOffer = warehouseItems.shuffled().take(15)
                        itemsToOffer.forEach { dbHelper.updateCargoStatus(it.id, "OFFERED") }
                        offeredContracts = dbHelper.getCargoByStatus("OFFERED")
                        android.widget.Toast.makeText(context, "Контракты обновлены из склада!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Обновить контракты")
        }

        if (selectedContract != null) {
            val cargo = selectedContract!!

            val estDistMeters = calculateEstimatedRoute(startPt, deliveryCargos)
            val newEst = calculateEstimatedRoute(startPt, deliveryCargos + cargo)
            val addedDistKm = (((newEst - estDistMeters) / 1000.0) * 10).roundToInt() / 10.0
            val addedTimeMins = ((addedDistKm / 11.0) * 60.0).roundToInt()

            AlertDialog(
                onDismissRequest = { selectedContract = null },
                title = { Text(cargo.name) },
                text = {
                    Column {
                        Text("Описание: ${cargo.description}")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Вес: ${cargo.weightKg} кг", style = MaterialTheme.typography.titleMedium)
                        Text("Хрупкий: ${if (cargo.isFragile) "Да" else "Нет"}", color = if (cargo.isFragile) androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Награда: ${cargo.xpReward} XP", color = MaterialTheme.colorScheme.primary)

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text("Удлинит маршрут на: +$addedDistKm км (~$addedTimeMins мин)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(4.dp))

                        Text("Будет загружено: ${totalPlannedWeight + cargo.weightKg} / $maxWeight кг", style = MaterialTheme.typography.bodySmall)
                        if (totalPlannedWeight + cargo.weightKg > maxWeight) {
                            Text("⚠️ ПЕРЕГРУЗ! Вы не сможете увезти всё за один раз.", color = androidx.compose.ui.graphics.Color.Red, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        dbHelper.updateCargoStatus(cargo.id, "ACCEPTED")

                        sessionAcceptedCargos = sessionAcceptedCargos + cargo
                        offeredContracts = dbHelper.getCargoByStatus("OFFERED")
                        warehouseCargos = dbHelper.getCargoByStatus("IN_WAREHOUSE")
                        selectedContract = null
                    }) { Text("Принять контракт") }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        dbHelper.updateCargoStatus(cargo.id, "IN_WAREHOUSE")

                        offeredContracts = dbHelper.getCargoByStatus("OFFERED")
                        selectedContract = null
                    }) { Text("Отказаться") }
                }
            )
        }
    }
}
fun createDistanceMarker(context: android.content.Context, text: String, color: Int): android.graphics.drawable.Drawable {
    val bitmap = android.graphics.Bitmap.createBitmap(160, 80, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        this.color = color
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    canvas.drawRoundRect(android.graphics.RectF(0f, 0f, 160f, 60f), 30f, 30f, paint)
    val path = android.graphics.Path().apply {
        moveTo(70f, 60f)
        lineTo(90f, 60f)
        lineTo(80f, 80f)
        close()
    }
    canvas.drawPath(path, paint)

    paint.color = android.graphics.Color.WHITE
    paint.textSize = 32f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.isFakeBoldText = true
    canvas.drawText(text, 80f, 42f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

fun createContractMarker(context: android.content.Context, name: String, dist: String, xp: Int, money: Int, color: Int): android.graphics.drawable.Drawable {
    val width = 360
    val height = 140
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val paint = android.graphics.Paint().apply {
        this.color = color
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }

    canvas.drawRoundRect(android.graphics.RectF(0f, 0f, width.toFloat(), 100f), 20f, 20f, paint)

    val path = android.graphics.Path().apply {
        moveTo((width / 2 - 15).toFloat(), 100f)
        lineTo((width / 2 + 15).toFloat(), 100f)
        lineTo((width / 2).toFloat(), 130f)
        close()
    }
    canvas.drawPath(path, paint)

    paint.color = android.graphics.Color.WHITE
    paint.textAlign = android.graphics.Paint.Align.CENTER

    paint.textSize = 28f
    paint.isFakeBoldText = true
    val shortName = if (name.length > 20) name.take(18) + "..." else name
    canvas.drawText(shortName, (width / 2).toFloat(), 35f, paint)

    paint.textSize = 24f
    paint.isFakeBoldText = false
    val statsText = "$dist | $xp XP | $money 💵"
    canvas.drawText(statsText, (width / 2).toFloat(), 75f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

fun createUserArrowMarker(context: android.content.Context): android.graphics.drawable.Drawable {
    val bitmap = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#007AFF"); style = android.graphics.Paint.Style.FILL; isAntiAlias = true }
    val path = android.graphics.Path().apply { moveTo(40f, 15f); lineTo(65f, 65f); lineTo(40f, 50f); lineTo(15f, 65f); close() }
    canvas.drawPath(path, paint)
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

fun createCustomMarker(context: android.content.Context, number: Int, color: Int): android.graphics.drawable.Drawable {
    val bitmap = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply { this.color = color; style = android.graphics.Paint.Style.FILL; isAntiAlias = true }
    canvas.drawCircle(40f, 40f, 35f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 40f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    canvas.drawText(number.toString(), 40f, 53f, paint)
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

fun getBearingBetween(p1: GeoPoint, p2: GeoPoint): Float {
    val loc1 = android.location.Location("").apply { latitude = p1.latitude; longitude = p1.longitude }
    val loc2 = android.location.Location("").apply { latitude = p2.latitude; longitude = p2.longitude }
    return loc1.bearingTo(loc2)
}

fun getPointAtAngle(center: GeoPoint, dist: Double, angle: Double): GeoPoint {
    val rad = 111300.0
    val dLat = (dist * Math.cos(Math.toRadians(angle))) / rad
    val dLon = (dist * Math.sin(Math.toRadians(angle))) / (rad * Math.cos(Math.toRadians(center.latitude)))
    return GeoPoint(center.latitude + dLat, center.longitude + dLon)
}

fun getDistanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
    val latToM = 111320.0
    val lonToM = 111320.0 * Math.cos(Math.toRadians(p.latitude))

    val px = p.longitude * lonToM
    val py = p.latitude * latToM
    val ax = a.longitude * lonToM
    val ay = a.latitude * latToM
    val bx = b.longitude * lonToM
    val by = b.latitude * latToM

    val l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
    if (l2 == 0.0) return p.distanceToAsDouble(a)

    val t = Math.max(0.0, Math.min(1.0, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l2))
    val projX = ax + t * (bx - ax)
    val projY = ay + t * (by - ay)

    return Math.sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
}

fun savePlayerMoney(context: android.content.Context, money: Int) {
    context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putInt("player_money", money).apply()
}

fun loadPlayerMoney(context: android.content.Context): Int {
    return context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getInt("player_money", 0)
}

fun savePlayerXp(context: android.content.Context, xp: Int) {
    context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putInt("player_xp", xp).apply()
}

fun loadPlayerXp(context: android.content.Context): Int {
    return context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getInt("player_xp", 0)
}

fun saveBaseLocation(context: android.content.Context, geoPoint: GeoPoint) {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    prefs.edit().putString("base_lat", geoPoint.latitude.toString()).putString("base_lon", geoPoint.longitude.toString()).apply()
}

fun loadBaseLocation(context: android.content.Context): GeoPoint? {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    val lat = prefs.getString("base_lat", null) ?: return null
    val lon = prefs.getString("base_lon", null) ?: return null
    return GeoPoint(lat.toDouble(), lon.toDouble())
}

fun saveSectors(context: android.content.Context, sectors: List<CitySector>) {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    prefs.edit().putString("city_sectors", Gson().toJson(sectors)).apply()
}

fun loadSectors(context: android.content.Context): List<CitySector> {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    val json = prefs.getString("city_sectors", null) ?: return emptyList()
    return try { Gson().fromJson(json, object : TypeToken<List<CitySector>>() {}.type) } catch (e: Exception) { emptyList() }
}

fun saveBikeUpgradeLevel(context: android.content.Context, level: Int) {
    context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putInt("bike_upgrade_level", level).apply()
}

fun loadBikeUpgradeLevel(context: android.content.Context): Int {
    return context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getInt("bike_upgrade_level", 1)
}

fun loadUpgradesDb(context: android.content.Context): List<BikeUpgrade> {
    return try {
        val jsonString = context.assets.open("upgrades_db.json").bufferedReader().use { it.readText() }
        Gson().fromJson(jsonString, object : TypeToken<List<BikeUpgrade>>() {}.type)
    } catch (e: Exception) {
        listOf(BikeUpgrade(1, "Стандартный багажник", 50.0, 0))
    }
}

fun getCurrentMaxWeight(context: android.content.Context): Double {
    val level = loadBikeUpgradeLevel(context)
    return loadUpgradesDb(context).find { it.level == level }?.maxWeightKg ?: 50.0
}

fun loadLevelsDb(context: android.content.Context): List<LevelTemplate> {
    return try {
        val jsonString = context.assets.open("levels_db.json").bufferedReader().use { it.readText() }
        Gson().fromJson(jsonString, object : TypeToken<List<LevelTemplate>>() {}.type)
    } catch (e: Exception) {
        (0..30).map { lvl ->
            val title = when(lvl) {
                0 -> "Новичок"
                in 1..9 -> "Младший курьер"
                in 10..19 -> "Специалист доставки"
                in 20..29 -> "Элитный курьер"
                else -> "Мастер курьер"
            }
            val xp = if (lvl == 0) 0 else (lvl * lvl * 50) + (lvl * 100)
            LevelTemplate(lvl, "$title $lvl", xp)
        }
    }
}

fun generateCityGrid(center: GeoPoint): List<CitySector> {
    val sectors = mutableListOf<CitySector>()
    var id = 0
    val R = 500.0
    val hexWidth = Math.sqrt(3.0) * R
    val vertSpacing = 1.5 * R

    val rings = 22
    for (row in -rings..rings) {
        for (col in -rings..rings) {
            val xOffset = if (row % 2 != 0) hexWidth / 2.0 else 0.0
            val xDist = (col * hexWidth) + xOffset
            val yDist = row * vertSpacing

            val ptY = getPointAtAngle(center, abs(yDist), if (yDist > 0) 0.0 else 180.0)
            val hexCenter = getPointAtAngle(ptY, abs(xDist), if (xDist > 0) 90.0 else 270.0)

            if (center.distanceToAsDouble(hexCenter) <= 11000.0) {
                sectors.add(CitySector(id++, hexCenter, true, 0))
            }
        }
    }
    return sectors
}

fun saveVisitedPoints(context: android.content.Context, points: List<GeoPoint>) {
    val json = Gson().toJson(points.map { mapOf("lat" to it.latitude, "lon" to it.longitude) })
    context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putString("visited_points", json).apply()
}

fun loadVisitedPoints(context: android.content.Context): List<GeoPoint> {
    val json = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getString("visited_points", null) ?: return emptyList()
    return try {
        val type = object : TypeToken<List<Map<String, Double>>>() {}.type
        val list: List<Map<String, Double>> = Gson().fromJson(json, type)
        list.map { GeoPoint(it["lat"]!!, it["lon"]!!) }
    } catch (e: Exception) { emptyList() }
}

fun saveTotalDistance(context: android.content.Context, distanceMeters: Double) {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    val current = prefs.getFloat("total_distance", 0f)
    prefs.edit().putFloat("total_distance", current + distanceMeters.toFloat()).apply()
}

fun loadTotalDistance(context: android.content.Context): Double {
    return context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getFloat("total_distance", 0f).toDouble()
}

fun addTotalDeliveries(context: android.content.Context, count: Int) {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    val current = prefs.getInt("total_deliveries", 0)
    prefs.edit().putInt("total_deliveries", current + count).apply()
}

fun loadTotalDeliveries(context: android.content.Context): Int {
    return context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getInt("total_deliveries", 0)
}

suspend fun fetchOSRMRoute(points: List<GeoPoint>): OsrmResult? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://api.openrouteservice.org/v2/directions/cycling-regular/geojson")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", Secrets.ORS_API_KEY)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json, application/geo+json, application/gpx+xml, img/png; charset=utf-8")
        conn.doOutput = true

        val jsonBody = JSONObject()
        val coordsArray = org.json.JSONArray()
        for (pt in points) {
            val ptArray = org.json.JSONArray()
            ptArray.put(pt.longitude)
            ptArray.put(pt.latitude)
            coordsArray.put(ptArray)
        }
        jsonBody.put("coordinates", coordsArray)
        jsonBody.put("language", "ru")
        jsonBody.put("continue_straight", true)

        conn.outputStream.use { os ->
            val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        if (conn.responseCode == 429) {
            return@withContext OsrmResult(emptyList(), -429.0, emptyList())
        }

        if (conn.responseCode != 200) {
            return@withContext null
        }

        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val features = json.optJSONArray("features") ?: return@withContext null
        val feature = features.getJSONObject(0)
        val geometry = feature.getJSONObject("geometry")
        val responseCoords = geometry.getJSONArray("coordinates")

        val path = mutableListOf<GeoPoint>()
        for (i in 0 until responseCoords.length()) {
            val pt = responseCoords.getJSONArray(i)
            path.add(GeoPoint(pt.getDouble(1), pt.getDouble(0)))
        }

        val properties = feature.getJSONObject("properties")
        val summary = properties.getJSONObject("summary")
        val totalDistance = summary.getDouble("distance")

        val wayPointsJson = properties.optJSONArray("way_points")
        val waypointIndices = mutableListOf<Int>()
        if (wayPointsJson != null) {
            for (i in 0 until wayPointsJson.length()) {
                waypointIndices.add(wayPointsJson.getInt(i))
            }
        }

        val stepsList = mutableListOf<RouteStep>()
        val segments = properties.getJSONArray("segments")

        for (segIdx in 0 until segments.length()) {
            val segment = segments.getJSONObject(segIdx)
            val stepsJson = segment.getJSONArray("steps")
            for (sIdx in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(sIdx)
                val instruction = step.getString("instruction")
                val wayPoints = step.getJSONArray("way_points")
                val geomIndex = wayPoints.getInt(0)
                val stepGeo = path[geomIndex]

                if (instruction.isNotEmpty()) {
                    stepsList.add(RouteStep(stepGeo, instruction))
                }
            }
        }

        return@withContext OsrmResult(path, totalDistance, stepsList, waypointIndices)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return@withContext null
}

@Composable
fun StatsScreen(onBack: () -> Unit, onShowHeatmap: () -> Unit) {
    val context = LocalContext.current
    val totalDistKm = (loadTotalDistance(context) / 1000.0).roundToInt()
    val totalDeliveries = loadTotalDeliveries(context)
    val playerXp = loadPlayerXp(context)
    val playerMoney = loadPlayerMoney(context)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ЛИЧНОЕ ДЕЛО", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            }
            Spacer(modifier = Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📊 Общая статистика", style = MaterialTheme.typography.titleLarge)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Пройдено километров: $totalDistKm км", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Доставлено грузов: $totalDeliveries шт.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Заработано опыта: $playerXp XP", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Текущий баланс: $playerMoney 💵", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onShowHeatmap,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF2C7FB8))
            ) {
                Text("Тепловая карта сети (DarkMint)", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun HeatmapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val citySectors = remember { loadSectors(context) }
    val baseLocation = remember { loadBaseLocation(context) }

    val darkMintPalette = listOf(
        "#D4F1D4",
        "#A8DDB5",
        "#7BCCC4",
        "#4EB3D3",
        "#2B8CBE",
        "#0868AC",
        "#084081"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(12.0)
                    if (baseLocation != null) controller.setCenter(baseLocation)

                    val maxVisits = citySectors.maxOfOrNull { it.visitCount }?.coerceAtLeast(1) ?: 1

                    citySectors.forEach { sector ->
                        if (sector.isActive) {
                            val colorIndex = if (sector.visitCount == 0) 0 else {
                                val ratio = sector.visitCount.toFloat() / maxVisits.toFloat()
                                (ratio * (darkMintPalette.size - 1)).roundToInt().coerceIn(0, darkMintPalette.size - 1)
                            }
                            val hexColorStr = darkMintPalette[colorIndex]

                            val hexPolygon = Polygon(this).apply {
                                val pts = mutableListOf<GeoPoint>()
                                for (i in 0..5) {
                                    pts.add(getPointAtAngle(sector.center, 500.0, 60.0 * i))
                                }
                                points = pts

                                fillPaint.color = android.graphics.Color.parseColor("#CC${hexColorStr.drop(1)}")
                                outlinePaint.color = android.graphics.Color.parseColor("#66000000")
                                outlinePaint.strokeWidth = 2f
                            }
                            overlays.add(hexPolygon)

                            if (sector.visitCount > 0) {
                                val textMarker = Marker(this).apply {
                                    position = sector.center
                                    icon = createCustomMarker(context, sector.visitCount, android.graphics.Color.parseColor(darkMintPalette[colorIndex]))
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                    infoWindow = null
                                    setOnMarkerClickListener { _, _ -> true }
                                }
                                overlays.add(textMarker)
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        FloatingActionButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
        }

        Card(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
        ) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Плотность доставок", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    darkMintPalette.forEach { colorStr ->
                        Box(modifier = Modifier.size(24.dp).background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorStr))))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(0.5f), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Мин", style = MaterialTheme.typography.bodySmall)
                    Text("Макс", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

class NavService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val channelId = "nav_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Навигация", android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }

        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }

        val title = intent?.getStringExtra("TITLE") ?: "Одекадек активен"
        val text = intent?.getStringExtra("TEXT") ?: "Отслеживание маршрута..."

        val notification = builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        startForeground(1, notification)
        return START_NOT_STICKY
    }
}