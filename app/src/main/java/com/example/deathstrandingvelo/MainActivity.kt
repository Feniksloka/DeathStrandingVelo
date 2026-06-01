package com.example.deathstrandingvelo
import kotlin.math.abs
import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.app.Notification
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


data class CargoTemplate(val name: String, val description: String, val weightKg: Double, val isFragile: Boolean, val baseXp: Int = 100)
data class CargoItem(val id: Int, val name: String, val description: String, val weightKg: Double, val isFragile: Boolean, val location: GeoPoint, val xpReward: Int, var status: CargoStatus = CargoStatus.PENDING)

// НОВЫЙ КЛАСС ДЛЯ УРОВНЕЙ
data class LevelTemplate(val level: Int, val title: String, val requiredXp: Int)

enum class CargoStatus { PENDING, COLLECTED, CANCELED }
data class CitySector(val id: Int, val center: GeoPoint, var isActive: Boolean = true, var visitCount: Int = 0)
// Умный класс маневров (помнит, о чем уже предупредил)
data class RouteStep(
    val location: GeoPoint,
    val instruction: String,
    var announced500: Boolean = false,
    var announced100: Boolean = false,
    var announcedNow: Boolean = false
)

// СТАЛО (Добавили waypointIndices):
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
                        MapScreen()
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

    // УБИВАЕМ ШТОРКУ ПРИ ЗАКРЫТИИ ПРИЛОЖЕНИЯ
    override fun onDestroy() {
        super.onDestroy()
        stopService(android.content.Intent(this, NavService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    val focusRequest = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
    }
    var playerXp by remember { mutableStateOf(loadPlayerXp(context)) }

    // Загружаем уровни из JSON или генерируем идеальную кривую по умолчанию
    val levelsDb by remember { mutableStateOf(
        try {
            val jsonString = context.assets.open("levels_db.json").bufferedReader().use { it.readText() }
            Gson().fromJson<List<LevelTemplate>>(jsonString, object : TypeToken<List<LevelTemplate>>() {}.type)
        } catch (e: Exception) {
            // Идеальная квадратичная прогрессия, если файла нет
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
    )}
    // ФУНКЦИЯ ОБНОВЛЕНИЯ ШТОРКИ
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

    var isFollowMode by remember { mutableStateOf(false) }
    var currentBearing by remember { mutableStateOf(0f) }
    var shouldInitCamera by remember { mutableStateOf(false) }
    var userPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }

    var cargoList by remember { mutableStateOf<List<CargoItem>>(emptyList()) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var navSteps by remember { mutableStateOf<List<RouteStep>>(emptyList()) }
    var lastOffRouteWarningTime by remember { mutableStateOf(0L) }

    var totalDistanceMeters by remember { mutableStateOf(0.0) }
    var distanceTraveledMeters by remember { mutableStateOf(0.0) }
    var previousLocation by remember { mutableStateOf<android.location.Location?>(null) }

    var selectedMinutes by remember { mutableStateOf(60) }
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
            val fragileWarning = if (nextCargo.isFragile) "Внимание, груз хрупкий." else ""
            tts.speak("Следующая цель: ${nextCargo.name}. ${nextCargo.description}. $fragileWarning", TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            tts.speak("Все грузы обработаны. Заказ выполнен, возвращайтесь на базу.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    val processCargo = { cargoId: Int, newStatus: CargoStatus ->
        val targetCargo = cargoList.find { it.id == cargoId }
        cargoList = cargoList.map { if (it.id == cargoId) it.copy(status = newStatus) else it }

        if (newStatus == CargoStatus.COLLECTED && targetCargo != null) {
            // Считаем старый уровень
            val oldLevel = levelsDb.lastOrNull { playerXp >= it.requiredXp } ?: levelsDb.first()

            // Начисляем опыт!
            playerXp += targetCargo.xpReward
            savePlayerXp(context, playerXp)
            updateNotification(playerXp) // <--- ДОБАВИТЬ ЭТУ СТРОЧКУ (Обновляем шторку!)
            // Считаем новый уровень
            val newLevel = levelsDb.lastOrNull { playerXp >= it.requiredXp } ?: levelsDb.first()

            if (newLevel.level > oldLevel.level) {
                tts.speak("Груз доставлен. Уровень повышен! Теперь вы: ${newLevel.title}.", TextToSpeech.QUEUE_FLUSH, null, "nav")
            } else {
                tts.speak("Груз доставлен. Получено ${targetCargo.xpReward} опыта.", TextToSpeech.QUEUE_FLUSH, null, "nav")
            }

            val newHistory = visitedPoints + targetCargo.location
            visitedPoints = newHistory
            saveVisitedPoints(context, newHistory)

        } else if (newStatus == CargoStatus.CANCELED) {
            tts.speak("Груз отменен.", TextToSpeech.QUEUE_FLUSH, null, "nav")
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ announceNext() }, 3500)
    }

    Box(modifier = Modifier.fillMaxSize()) {
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

                    // --- МАГИЯ КИСТИ (ПЕРЕМЕННЫЕ ДЛЯ РИСОВАНИЯ) ---
                    var isPainting = false
                    var paintTargetState = false
                    var lastPaintedSectorId = -1

                    // 1. Оверлей для отслеживания движения пальца (Рисование)
                    val paintOverlay = object : org.osmdroid.views.overlay.Overlay() {
                        override fun onTouchEvent(event: android.view.MotionEvent, mapView: MapView): Boolean {
                            if (!isGridEditMode) return super.onTouchEvent(event, mapView)

                            when (event.action) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    isPainting = false // Сбрасываем при новом касании
                                }
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (isPainting) {
                                        val proj = mapView.projection
                                        val geo = proj.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                                        val sector = citySectors.find { it.center.distanceToAsDouble(geo) < 433.0 }

                                        // Если палец зашел на новый гекс - красим его на лету!
                                        if (sector != null && sector.id != lastPaintedSectorId && sector.isActive != paintTargetState) {
                                            lastPaintedSectorId = sector.id
                                            val updated = citySectors.map {
                                                if (it.id == sector.id) it.copy(isActive = paintTargetState) else it
                                            }
                                            citySectors = updated
                                        }
                                        return true // Блокируем сдвиг карты, пока рисуем!
                                    }
                                }
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                    if (isPainting) {
                                        isPainting = false
                                        saveSectors(context, citySectors) // Сохраняем в память только когда отпустили палец (чтобы не лагало)
                                        return true
                                    }
                                }
                            }
                            return super.onTouchEvent(event, mapView)
                        }
                    }
                    overlays.add(paintOverlay)

                    // 2. Обработчик кликов и долгого нажатия (Активация кисти)
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
                                    // ВКЛЮЧАЕМ РЕЖИМ КИСТИ!
                                    isPainting = true
                                    paintTargetState = !centerSector.isActive
                                    lastPaintedSectorId = centerSector.id

                                    post {
                                        val updated = citySectors.map {
                                            if (it.id == centerSector.id) it.copy(isActive = paintTargetState) else it
                                        }
                                        citySectors = updated
                                    }

                                    // Легкая вибрация, чтобы ты понял, что кисть активирована
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
                                            // 1. ПРОВЕРКА ОТКЛОНЕНИЯ ОТ МАРШРУТА (Умная)
                                            var minDistanceToLine = Double.MAX_VALUE
                                            if (routePoints.size > 1) {
                                                for (i in 0 until routePoints.size - 1) {
                                                    val d = getDistanceToSegment(currentGeo, routePoints[i], routePoints[i+1])
                                                    if (d < minDistanceToLine) minDistanceToLine = d
                                                }
                                            }

                                            // УМНЫЙ АНТИ-СПАМ:
                                            // loc.accuracy < 50f -> Игнорируем "прыжки" GPS, если сигнал слабый (телефон в кармане)
                                            // minDistanceToLine > 150.0 -> Увеличили допуск до 150 метров (велосипед часто едет по параллельному тротуару)
                                            if (loc.accuracy < 50f && minDistanceToLine > 150.0) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastOffRouteWarningTime > 60000) { // Не бесим игрока, предупреждаем максимум раз в минуту!
                                                    tts.speak("Внимание! Вы отклонились от маршрута.", TextToSpeech.QUEUE_FLUSH, null, "nav")
                                                    lastOffRouteWarningTime = now
                                                }
                                            }
                                            // 2. TURN-BY-TURN НАВИГАЦИЯ (3 стадии предупреждения)
                                            val nextStep = navSteps.firstOrNull { !it.announcedNow }
                                            if (nextStep != null) {
                                                val distToTurn = currentGeo.distanceToAsDouble(nextStep.location)
                                                val stepIndex = navSteps.indexOf(nextStep)
                                                val updatedList = navSteps.toMutableList()
                                                var changed = false

                                                // Стадия 1: Дальнее предупреждение (от 300 до 600 метров)
                                                if (distToTurn in 300.0..600.0 && !nextStep.announced500) {
                                                    val rounded = (distToTurn / 50).roundToInt() * 50
                                                    tts.speak("Через $rounded метров ${nextStep.instruction}", TextToSpeech.QUEUE_ADD, null, "nav")
                                                    updatedList[stepIndex] = nextStep.copy(announced500 = true)
                                                    changed = true
                                                }
                                                // Стадия 2: Ближнее предупреждение (от 50 до 150 метров)
                                                else if (distToTurn in 50.0..150.0 && !nextStep.announced100) {
                                                    val rounded = (distToTurn / 10).roundToInt() * 10
                                                    tts.speak("Через $rounded метров ${nextStep.instruction}", TextToSpeech.QUEUE_ADD, null, "nav")
                                                    updatedList[stepIndex] = nextStep.copy(announced500 = true, announced100 = true)
                                                    changed = true
                                                }
                                                // Стадия 3: Прямо на повороте (меньше 25 метров)
                                                else if (distToTurn < 25.0 && !nextStep.announcedNow) {
                                                    tts.speak(nextStep.instruction, TextToSpeech.QUEUE_ADD, null, "nav")
                                                    updatedList[stepIndex] = nextStep.copy(announced500 = true, announced100 = true, announcedNow = true)
                                                    changed = true
                                                }

                                                // Если мы пролетели поворот (удалились от него, а он так и не был озвучен как "Now")
                                                if (distToTurn > 100.0 && nextStep.announced100 && !nextStep.announcedNow) {
                                                    updatedList[stepIndex] = nextStep.copy(announcedNow = true)
                                                    changed = true
                                                }

                                                if (changed) navSteps = updatedList
                                            }
                                        }
                                        previousLocation = loc
                                        val nextCargo = cargoList.firstOrNull { it.status == CargoStatus.PENDING }
                                        if (nextCargo != null) {
                                            if (currentGeo.distanceToAsDouble(nextCargo.location) <= 25.0) {
                                                processCargo(nextCargo.id, CargoStatus.COLLECTED)
                                            }
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

                // 3. ОТРИСОВКА СЕТКИ ГОРОДА (ШЕСТИУГОЛЬНИКИ)
                if (isGridEditMode || !isRouteBuilt) {
                    citySectors.forEach { sector ->
                        val hexPolygon = Polygon(mapView).apply {
                            val pts = mutableListOf<GeoPoint>()
                            for (i in 0..5) {
                                // ТУТ ТОЖЕ СТАВИМ 500.0, чтобы размер рисунка совпал с физикой!
                                pts.add(getPointAtAngle(sector.center, 525.0, 30.0 + 60.0 * i))
                            }
                            points = pts

                            if (sector.isActive) {
                                val alpha = maxOf(10, 68 - (sector.visitCount * 10))
                                val hexAlpha = alpha.toString(16).padStart(2, '0')
                                fillPaint.color = android.graphics.Color.parseColor("#${hexAlpha}00FF00")
                                outlinePaint.color = android.graphics.Color.parseColor("#4400FF00")
                            } else {
                                fillPaint.color = android.graphics.Color.parseColor("#33FF0000")
                                outlinePaint.color = android.graphics.Color.parseColor("#88FF0000")
                            }
                            outlinePaint.strokeWidth = 3f
                        }
                        mapView.overlays.add(hexPolygon)

                        if (sector.isActive) {
                            val textMarker = Marker(mapView).apply {
                                position = sector.center
                                icon = createCustomMarker(context, sector.visitCount, android.graphics.Color.parseColor("#00AA00"))
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                infoWindow = null // УБИВАЕМ ПУСТЫЕ БЕЛЫЕ ПУЗЫРИ!

                                // Заставляем цифру переключать цвет гекса при клике
                                setOnMarkerClickListener { _, _ ->
                                    if (isGridEditMode) {
                                        val updated = citySectors.map { if (it.id == sector.id) it.copy(isActive = !it.isActive) else it }
                                        citySectors = updated
                                        saveSectors(context, updated)
                                    }
                                    true // Поглощаем клик
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
                    // СНАЧАЛА РИСУЕМ ОРАНЖЕВУЮ (Она ляжет вниз)
                    if (nextCargoIndex < routePoints.size - 1) {
                        val futureLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(nextCargoIndex, routePoints.size))
                            outlinePaint.color = android.graphics.Color.parseColor("#FFA500")
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(futureLine)
                    }

                    // ПОТОМ РИСУЕМ ЗЕЛЕНУЮ (Она ляжет поверх оранжевой!)
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
                        infoWindow = null // Отключаем пузырь
                        setOnMarkerClickListener { _, _ -> true } // Игнорируем клики по себе
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
            Card(modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .fillMaxWidth(0.9f)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text("Генерация заказа", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                        IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset(x = 8.dp, y = (-8).dp)) {
                            Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 90, 120).forEach { mins ->
                            FilterChip(selected = selectedMinutes == mins, onClick = { selectedMinutes = mins }, label = { Text("$mins мин") })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else {
                        Button(
                            onClick = {
                                val start = userPosition ?: return@Button
                                isLoading = true
                                coroutineScope.launch {
                                    generateSmartRoute(start, selectedMinutes, citySectors, visitedPoints) { success, errorMsg, bestPoints, route, totalDistance, generatedSteps, chosenIds ->
                                        if (!success) {
                                            isLoading = false
                                            android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_LONG).show()
                                            return@generateSmartRoute
                                        }

                                        val updatedSectors = citySectors.map { if (it.id in chosenIds) it.copy(visitCount = it.visitCount + 1) else it }
                                        citySectors = updatedSectors
                                        saveSectors(context, updatedSectors)

                                        val allTemplates: List<CargoTemplate> = try {
                                            val jsonString = context.assets.open("cargo_db.json").bufferedReader().use { it.readText() }
                                            val templateType = object : TypeToken<List<CargoTemplate>>() {}.type
                                            Gson().fromJson(jsonString, templateType)
                                        } catch (e: Exception) {
                                            listOf(CargoTemplate("Аварийный груз", "База данных не найдена.", 5.0, false), CargoTemplate("Утерянный контейнер", "Резервная генерация.", 2.5, true))
                                        }

                                        val shuffledTemplates = allTemplates.shuffled()
                                        cargoList = bestPoints.mapIndexed { index, pt ->
                                            val template = if (shuffledTemplates.isNotEmpty()) shuffledTemplates[index % shuffledTemplates.size] else CargoTemplate("Пусто", "Пусто", 0.0, false)
                                            // Даем базовый опыт + бонус за хрупкость + случайный бонус от 10 до 50 XP
                                            val xpReward = template.baseXp + (if (template.isFragile) 50 else 0) + Random.nextInt(10, 50)
                                            CargoItem(index + 1, template.name, template.description, template.weightKg, template.isFragile, pt, xpReward)   }

                                        routePoints = route
                                        navSteps = generatedSteps
                                        totalDistanceMeters = totalDistance
                                        distanceTraveledMeters = 0.0
                                        previousLocation = null
                                        isFollowMode = true
                                        shouldInitCamera = true
                                        hasZoomedToRoute = true

                                        val firstPt = route.firstOrNull()
                                        if (firstPt != null) currentBearing = getBearingBetween(start, firstPt)

                                        isRouteBuilt = true
                                        isLoading = false
                                        updateNotification(playerXp)

                                        val tKm = ((totalDistance / 1000.0) * 10).roundToInt() / 10.0

                                        val firstInstruction = generatedSteps.firstOrNull()?.instruction ?: "следуйте по маршруту"
                                        tts.speak("Маршрут $tKm километров построен. Старт движения. Через 50 метров $firstInstruction.", TextToSpeech.QUEUE_FLUSH, null, "nav")
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ announceNext() }, 5000)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Сформировать заказ") }
                    }
                }
            }
        }

        // БОТТОМ ПАНЕЛЬ: Мониторинг
        if (isRouteBuilt) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(0.9f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // --- ПРОФИЛЬ И ОПЫТ ---
                    val currentLevel = levelsDb.lastOrNull { playerXp >= it.requiredXp } ?: levelsDb.first()
                    val nextLevel = levelsDb.firstOrNull { it.level == currentLevel.level + 1 }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("⭐ ${currentLevel.title}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("$playerXp XP", style = MaterialTheme.typography.bodyMedium)
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

                    // --- СТАТИСТИКА ПОЕЗДКИ ---
                    val totalKm = ((totalDistanceMeters / 1000.0) * 100).roundToInt() / 100.0
                    val traveledKm = ((distanceTraveledMeters / 1000.0) * 100).roundToInt() / 100.0
                    val remainingKm = (((totalDistanceMeters - distanceTraveledMeters).coerceAtLeast(0.0)) / 1000.0 * 100).roundToInt() / 100.0
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

                    // ПОДСКАЗКА ПРИ ВКЛЮЧЕНИИ РЕЖИМА
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
                                Text("Статус: ${cargo.status}")
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
                            OutlinedTextField(value = selectedVoiceName, onValueChange = {}, readOnly = true, label = { Text("Выберите голос") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isVoiceDropdownExpanded) }, modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth())
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
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Закрыть") } }
        )
    }

    if (ttsDiagText.isNotEmpty()) {
        AlertDialog(onDismissRequest = { ttsDiagText = "" }, title = { Text("Вскрытие показало:") }, text = { Text(ttsDiagText) }, confirmButton = { Button(onClick = { ttsDiagText = "" }) { Text("Закрыть") } })
    }
}

suspend fun generateSmartRoute(
    start: GeoPoint, minutes: Int, sectors: List<CitySector>, visitedHistory: List<GeoPoint>,
    onResult: (Boolean, String, List<GeoPoint>, List<GeoPoint>, Double, List<RouteStep>, List<Int>) -> Unit
) {
    val targetDist = (minutes / 60.0) * 12.0 * 1000.0

    // Динамическое количество секторов: для 30 минут достаточно 2 гексов
    val requiredSectors = if (minutes <= 30) 2 else 3

    // Максимальное удаление гекса - 45% от целевой дистанции (чтобы хватило на возврат)
    val maxDistFromStart = targetDist * 0.45

    val activeSectors = sectors.filter { it.isActive && start.distanceToAsDouble(it.center) <= maxDistFromStart }

    if (activeSectors.size < requiredSectors) {
        val distKm = (maxDistFromStart / 1000.0).roundToInt()
        onResult(false, "Для $minutes мин. нужно минимум $requiredSectors зеленых гекса в радиусе $distKm км от вас.", emptyList(), emptyList(), 0.0, emptyList(), emptyList())
        return
    }

    // Взвешенный пул секторов (учитываем посещаемость)
    val maxVisits = activeSectors.maxOfOrNull { it.visitCount } ?: 0
    val pool = mutableListOf<CitySector>()
    activeSectors.forEach { sector ->
        val weight = (maxVisits - sector.visitCount) + 1
        repeat(weight) { pool.add(sector) }
    }

    // Класс для хранения локальных кандидатов
    data class RouteCandidate(val sectors: List<CitySector>, val score: Double)
    val candidates = mutableListOf<RouteCandidate>()

    // Генерируем 100 комбинаций локально и оцениваем их геометрию
    for (i in 1..100) {
        val currentChosen = mutableListOf<CitySector>()
        val tempPool = pool.toMutableList()
        while (currentChosen.size < requiredSectors && tempPool.isNotEmpty()) {
            val pick = tempPool.random()
            currentChosen.add(pick)
            tempPool.removeAll { it.id == pick.id }
        }

        if (currentChosen.size < requiredSectors) continue

        // Считаем длину периметра по прямой
        var straightDist = start.distanceToAsDouble(currentChosen.first().center)
        for (j in 0 until currentChosen.size - 1) {
            straightDist += currentChosen[j].center.distanceToAsDouble(currentChosen[j+1].center)
        }
        straightDist += currentChosen.last().center.distanceToAsDouble(start)

        // Идеальная длина по прямой - это примерно 80% от длины по дорогам.
        // Чем ближе дистанция к этому идеалу, тем меньше score (лучше результат)
        val score = Math.abs(straightDist - (targetDist * 0.8))
        candidates.add(RouteCandidate(currentChosen, score))
    }

    // Берем 5 самых геометрически правильных комбинаций
    val bestCandidates = candidates.sortedBy { it.score }.take(5)

    var bestRes: OsrmResult? = null
    var chosenSectorIds = listOf<Int>()

    // Отправляем на сервер только лучшие варианты (максимум 5 запросов)
    for (candidate in bestCandidates) {
        val waypoints = mutableListOf<GeoPoint>()
        waypoints.add(start)
        waypoints.addAll(candidate.sectors.map { it.center })
        waypoints.add(start)

        val res = fetchOSRMRoute(waypoints)

        if (res != null) {
            if (res.distanceMeters == -429.0) {
                onResult(false, "Лимит запросов API. Подождите 1 минуту.", emptyList(), emptyList(), 0.0, emptyList(), emptyList())
                return
            }

            // Если маршрут вписывается в допуски (от 70% до 140% от времени) - берем его и останавливаем поиск
            if (res.distanceMeters >= targetDist * 0.7 && res.distanceMeters <= targetDist * 1.4) {
                bestRes = res
                chosenSectorIds = candidate.sectors.map { it.id }
                break
            }

            // Сохраняем лучший из неподходящих на случай, если идеального не найдется
            if (bestRes == null || Math.abs(res.distanceMeters - targetDist) < Math.abs(bestRes.distanceMeters - targetDist)) {
                bestRes = res
                chosenSectorIds = candidate.sectors.map { it.id }
            }
        }
    }

    if (bestRes == null) {
        onResult(false, "Не удалось получить данные от сервера маршрутов. Проверьте интернет.", emptyList(), emptyList(), 0.0, emptyList(), emptyList())
        return
    }

    // Расстановка грузов
    val cargoPoints = mutableListOf<GeoPoint>()
    val wpIndices = bestRes.waypointIndices

    val anchorIndices = mutableListOf<Int>()
    if (wpIndices.size >= 3) {
        for (i in 1 until wpIndices.size - 1) {
            anchorIndices.add(wpIndices[i])
        }
    }

    var currentSegmentDist = 0.0
    // Для коротких маршрутов грузы спавнятся чаще
    val cargoInterval = if (minutes <= 30) 2000.0 else 3500.0
    var nextTarget = Random.nextDouble(cargoInterval * 0.8, cargoInterval * 1.2)

    for (i in 0 until bestRes.path.size - 1) {
        val d = bestRes.path[i].distanceToAsDouble(bestRes.path[i+1])
        currentSegmentDist += d

        if (anchorIndices.contains(i + 1)) {
            cargoPoints.add(bestRes.path[i+1])
            currentSegmentDist = 0.0
            nextTarget = Random.nextDouble(cargoInterval * 0.8, cargoInterval * 1.2)
        }
        else if (currentSegmentDist >= nextTarget) {
            cargoPoints.add(bestRes.path[i+1])
            currentSegmentDist = 0.0
            nextTarget = Random.nextDouble(cargoInterval * 0.8, cargoInterval * 1.2)
        }
    }

    if (cargoPoints.isEmpty() && bestRes.path.size > 2) {
        cargoPoints.add(bestRes.path[bestRes.path.size / 2])
    }

    onResult(true, "", cargoPoints, bestRes.path, bestRes.distanceMeters, bestRes.steps, chosenSectorIds)
}

fun getBearingBetween(p1: GeoPoint, p2: GeoPoint): Float {
    val loc1 = android.location.Location("").apply { latitude = p1.latitude; longitude = p1.longitude }
    val loc2 = android.location.Location("").apply { latitude = p2.latitude; longitude = p2.longitude }
    return loc1.bearingTo(loc2)
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

fun saveSectors(context: android.content.Context, sectors: List<CitySector>) {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    prefs.edit().putString("city_sectors", Gson().toJson(sectors)).apply()
}

fun loadSectors(context: android.content.Context): List<CitySector> {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    val json = prefs.getString("city_sectors", null) ?: return emptyList()
    return try { Gson().fromJson(json, object : TypeToken<List<CitySector>>() {}.type) } catch (e: Exception) { emptyList() }
}
fun getPointAtAngle(center: GeoPoint, dist: Double, angle: Double): GeoPoint {
    val rad = 111300.0
    val dLat = (dist * Math.cos(Math.toRadians(angle))) / rad
    val dLon = (dist * Math.sin(Math.toRadians(angle))) / (rad * Math.cos(Math.toRadians(center.latitude)))
    return GeoPoint(center.latitude + dLat, center.longitude + dLon)
}
fun generateCityGrid(center: GeoPoint): List<CitySector> {
    val sectors = mutableListOf<CitySector>()
    var id = 0
    val R = 500.0 // Радиус гекса (500 метров)
    val hexWidth = Math.sqrt(3.0) * R
    val vertSpacing = 1.5 * R

    // 22 кольца по 500м = 11 км во все стороны (диаметр 22 км)
    val rings = 22
    for (row in -rings..rings) {
        for (col in -rings..rings) {
            // Смещение для идеальной стыковки сот (как кирпичная кладка)
            val xOffset = if (row % 2 != 0) hexWidth / 2.0 else 0.0
            val xDist = (col * hexWidth) + xOffset
            val yDist = row * vertSpacing

            // Вычисляем координаты центра гекса
            val ptY = getPointAtAngle(center, abs(yDist), if (yDist > 0) 0.0 else 180.0)
            val hexCenter = getPointAtAngle(ptY, abs(xDist), if (xDist > 0) 90.0 else 270.0)

            // Обрезаем всё, что дальше 11 км от старта, чтобы получился ровный огромный круг
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

// НОВЫЙ СУПЕР-МОЗГ: OpenRouteService (POST-запрос)
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

        // УБИВАЕМ АППЕНДИКСЫ: Запрещаем развороты в тупиках, заставляем объезжать кварталы!
        jsonBody.put("continue_straight", true)

        conn.outputStream.use { os ->
            val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        // ЛОВИМ БАН ОТ СЕРВЕРА (429 - Слишком много запросов)
        if (conn.responseCode == 429) {
            return@withContext OsrmResult(emptyList(), -429.0, emptyList()) // Специальный код-флаг
        }

        if (conn.responseCode != 200) {
            val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() }
            android.util.Log.e("ORS_ERROR", "Код: ${conn.responseCode}, Ошибка: $errorMsg")
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
// --- ДОБАВИТЬ ЭТОТ БЛОК ---
        // Достаем индексы опорных точек (те самые концы аппендиксов)
        val wayPointsJson = properties.optJSONArray("way_points")
        val waypointIndices = mutableListOf<Int>()
        if (wayPointsJson != null) {
            for (i in 0 until wayPointsJson.length()) {
                waypointIndices.add(wayPointsJson.getInt(i))
            }
        }
        // -------------------------
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

        return@withContext OsrmResult(path, totalDistance, stepsList, waypointIndices) } catch (e: Exception) {
        e.printStackTrace()
    }
    return@withContext null
}

// Умный расчет расстояния от точки до отрезка линии (чтобы не сбиваться на прямых дорогах)
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

fun loadVisitedPoints(context: android.content.Context): List<GeoPoint> {
    val json = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getString("visited_points", null) ?: return emptyList()
    return try {
        val list = Gson().fromJson<List<Map<String, Double>>>(json, object : TypeToken<List<Map<String, Double>>>() {}.type)
        list.map { GeoPoint(it["lat"]!!, it["lon"]!!) }
    } catch (e: Exception) { emptyList() }
}
fun savePlayerXp(context: android.content.Context, xp: Int) {
    context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putInt("player_xp", xp).apply()
}

fun loadPlayerXp(context: android.content.Context): Int {
    return context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getInt("player_xp", 0)
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

        // Читаем текст из Intent (или ставим дефолтный)
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