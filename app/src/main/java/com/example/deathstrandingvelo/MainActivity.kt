package com.example.deathstrandingvelo
import kotlin.math.abs
import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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

data class CargoTemplate(val name: String, val description: String, val weightKg: Double, val isFragile: Boolean)
enum class CargoStatus { PENDING, COLLECTED, CANCELED }
data class CargoItem(val id: Int, val name: String, val description: String, val weightKg: Double, val isFragile: Boolean, val location: GeoPoint, var status: CargoStatus = CargoStatus.PENDING)
data class CitySector(val id: Int, val center: GeoPoint, var isActive: Boolean = true, var visitCount: Int = 0)
data class RouteStep(val location: GeoPoint, val instruction: String, var isSpoken: Boolean = false)
data class OsrmResult(val path: List<GeoPoint>, val distanceMeters: Double, val steps: List<RouteStep>)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val locationPermissionsState = rememberMultiplePermissionsState(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    if (locationPermissionsState.allPermissionsGranted) {
                        MapScreen()
                    } else {
                        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("Для сканера нужен GPS.")
                            Button(onClick = { locationPermissionsState.launchMultiplePermissionRequest() }) { Text("Активировать") }
                        }
                    }
                }
            }
        }
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
        if (newStatus == CargoStatus.COLLECTED) {
            tts.speak("Груз собран.", TextToSpeech.QUEUE_FLUSH, null, "nav")
            if (targetCargo != null) {
                val newHistory = visitedPoints + targetCargo.location
                visitedPoints = newHistory
                saveVisitedPoints(context, newHistory)
            }
        } else if (newStatus == CargoStatus.CANCELED) {
            tts.speak("Груз отменен.", TextToSpeech.QUEUE_FLUSH, null, "nav")
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ announceNext() }, 2500)
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
                                            var minDistanceToLine = Double.MAX_VALUE
                                            for (pt in routePoints) {
                                                val d = currentGeo.distanceToAsDouble(pt)
                                                if (d < minDistanceToLine) minDistanceToLine = d
                                            }
                                            if (minDistanceToLine > 70.0) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastOffRouteWarningTime > 20000) {
                                                    tts.speak("Внимание! Вы сбились с маршрута.", TextToSpeech.QUEUE_FLUSH, null, "nav")
                                                    lastOffRouteWarningTime = now
                                                }
                                            }
                                            val nextStep = navSteps.firstOrNull { !it.isSpoken }
                                            if (nextStep != null) {
                                                val distToTurn = currentGeo.distanceToAsDouble(nextStep.location)
                                                if (distToTurn in 20.0..120.0) {
                                                    val roundedDist = (distToTurn / 10).roundToInt() * 10
                                                    tts.speak("Через $roundedDist метров ${nextStep.instruction}", TextToSpeech.QUEUE_ADD, null, "nav")
                                                    val stepIndex = navSteps.indexOf(nextStep)
                                                    if (stepIndex != -1) {
                                                        val updatedList = navSteps.toMutableList()
                                                        updatedList[stepIndex] = nextStep.copy(isSpoken = true)
                                                        navSteps = updatedList
                                                    }
                                                } else if (distToTurn < 20.0) {
                                                    val stepIndex = navSteps.indexOf(nextStep)
                                                    if (stepIndex != -1) {
                                                        val updatedList = navSteps.toMutableList()
                                                        updatedList[stepIndex] = nextStep.copy(isSpoken = true)
                                                        navSteps = updatedList
                                                    }
                                                }
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
                    if (closestUserIndex < nextCargoIndex) {
                        val currentLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(closestUserIndex, nextCargoIndex + 1))
                            outlinePaint.color = android.graphics.Color.parseColor("#00FF00")
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(currentLine)
                    }
                    if (nextCargoIndex < routePoints.size - 1) {
                        val futureLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(nextCargoIndex, routePoints.size))
                            outlinePaint.color = android.graphics.Color.parseColor("#FFA500")
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(futureLine)
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
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 190.dp)
            ) { Icon(Icons.Filled.Navigation, contentDescription = "Режим навигации") }
        }

        if (userPosition != null && !isRouteBuilt && !isGridEditMode) {
            Card(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth(0.9f)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text("Генерация заказа", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                        IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.align(Alignment.CenterEnd).offset(x = 8.dp, y = (-8).dp)) {
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
                                            CargoItem(index + 1, template.name, template.description, template.weightKg, template.isFragile, pt)
                                        }

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

        if (isRouteBuilt) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(0.9f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📱 ОДЕКАДЕК: МОНИТОРИНГ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    val totalKm = ((totalDistanceMeters / 1000.0) * 100).roundToInt() / 100.0
                    val traveledKm = ((distanceTraveledMeters / 1000.0) * 100).roundToInt() / 100.0
                    val remainingKm = (((totalDistanceMeters - distanceTraveledMeters).coerceAtLeast(0.0)) / 1000.0 * 100).roundToInt() / 100.0
                    Text("🏁 Длина: $totalKm км | 🚴 Проехано: $traveledKm км")
                    Text("📦 Осталось маршрута: $remainingKm км", color = if (remainingKm > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    val pendingCount = cargoList.count { it.status == CargoStatus.PENDING }
                    Text("📦 Грузов осталось: $pendingCount / ${cargoList.size}")
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
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
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
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { showListDialog = false; selectedCargo = cargo },
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
    // 12 км/ч = 12 км за 60 минут
    val targetDist = (minutes / 60.0) * 12.0 * 1000.0
    val activeSectors = sectors.filter { it.isActive }

    if (activeSectors.size < 3) {
        onResult(false, "Включите минимум 3 зеленых гекса на карте!", emptyList(), emptyList(), 0.0, emptyList(), emptyList())
        return
    }

    var bestRes: OsrmResult? = null
    var chosenSectorIds = listOf<Int>()
    var currentRadiusMod = 1.0

    for (attempt in 1..15) { // 15 попыток, чтобы точно подобрать идеальный километраж
        // 1. ИДЕАЛЬНАЯ ГЕОМЕТРИЯ: Строим треугольник нужного размера вокруг дома
        val radius = (targetDist / (2 * Math.PI)) * currentRadiusMod
        val startAngle = Random.nextDouble(0.0, 360.0)

        val idealPt1 = getPointAtAngle(start, radius, startAngle)
        val idealPt2 = getPointAtAngle(start, radius * 1.2, startAngle + 120.0)
        val idealPt3 = getPointAtAngle(start, radius, startAngle + 240.0)

        // 2. ПОИСК ГЕКСОВ: Ищем реальные гексы рядом с идеальными точками
        // ШТРАФ ЗА ВИЗИТЫ: Если ты там был, гекс кажется алгоритму "дальше" на 1.5 км
        fun findBestSector(idealPt: GeoPoint, excludeIds: List<Int>): CitySector? {
            return activeSectors
                .filter { it.id !in excludeIds }
                .minByOrNull { sector ->
                    val dist = sector.center.distanceToAsDouble(idealPt)
                    dist + (sector.visitCount * 1500.0) // Штраф за посещаемость!
                }
        }

        val s1 = findBestSector(idealPt1, emptyList())
        val s2 = if (s1 != null) findBestSector(idealPt2, listOf(s1.id)) else null
        val s3 = if (s1 != null && s2 != null) findBestSector(idealPt3, listOf(s1.id, s2.id)) else null

        if (s1 == null || s2 == null || s3 == null) continue

        val currentChosen = listOf(s1, s2, s3)
        val waypoints = listOf(start, s1.center, s2.center, s3.center, start)

        // 3. СТРОИМ МАРШРУТ
        val res = fetchOSRMRoute(waypoints)
        if (res != null) {
            // 4. ЖЕСТКАЯ ПРОВЕРКА ДИСТАНЦИИ
            // Если маршрут длиннее чем надо на 35% - бракуем и сужаем круг!
            if (res.distanceMeters <= targetDist * 1.35) {
                bestRes = res
                chosenSectorIds = currentChosen.map { it.id }
                break
            } else {
                currentRadiusMod *= 0.75 // Сжимаем треугольник на 25% и пробуем снова
            }
        }
    }

    if (bestRes == null) {
        onResult(false, "Не удалось проложить маршрут на $minutes мин. Увеличьте время или включите больше гексов рядом с домом.", emptyList(), emptyList(), 0.0, emptyList(), emptyList())
        return
    }

    // 5. ГРУЗЫ КАЖДЫЕ 3-5 КМ ПРЯМО НА ДОРОГЕ
    val cargoPoints = mutableListOf<GeoPoint>()
    var currentSegmentDist = 0.0

    // Первый груз появится через случайное расстояние от 3 до 5 км
    var nextTarget = Random.nextDouble(3000.0, 5000.0)

    for (i in 0 until bestRes.path.size - 1) {
        val d = bestRes.path[i].distanceToAsDouble(bestRes.path[i+1])
        currentSegmentDist += d
        if (currentSegmentDist >= nextTarget) {
            cargoPoints.add(bestRes.path[i+1]) // Кидаем груз ровно на дорогу!
            currentSegmentDist = 0.0
            nextTarget = Random.nextDouble(3000.0, 5000.0) // Следующий груз снова через 3-5 км
        }
    }

    // Гарантируем, что хоть один груз есть
    if (cargoPoints.isEmpty() && bestRes.path.size > 2) {
        cargoPoints.add(bestRes.path[bestRes.path.size / 2])
    }

    onResult(true, "", cargoPoints, bestRes.path, bestRes.distanceMeters, bestRes.steps, chosenSectorIds)
}

suspend fun fetchOSRMRoute(points: List<GeoPoint>): OsrmResult? = withContext(Dispatchers.IO) {
    try {
        val coords = points.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = URL("https://router.project-osrm.org/route/v1/cycling/$coords?overview=full&geometries=geojson&steps=true")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("User-Agent", "BikeStrandingApp MVP")
        if (conn.responseCode != 200) return@withContext null
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        if (json.getString("code") != "Ok") return@withContext null

        val route = json.getJSONArray("routes").getJSONObject(0)
        val path = mutableListOf<GeoPoint>()
        val coordsArray = route.getJSONObject("geometry").getJSONArray("coordinates")
        for (i in 0 until coordsArray.length()) {
            path.add(GeoPoint(coordsArray.getJSONArray(i).getDouble(1), coordsArray.getJSONArray(i).getDouble(0)))
        }

        val stepsList = mutableListOf<RouteStep>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val stepsJson = legs.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until stepsJson.length()) {
                val maneuver = stepsJson.getJSONObject(s).getJSONObject("maneuver")
                val modifier = maneuver.optString("modifier", "")
                val locArray = maneuver.getJSONArray("location")
                val stepGeo = GeoPoint(locArray.getDouble(1), locArray.getDouble(0))
                var instruction = ""
                when (modifier) {
                    "right", "sharp right", "slight right" -> instruction = "поверните направо"
                    "left", "sharp left", "slight left" -> instruction = "поверните налево"
                    "uturn" -> instruction = "развернитесь"
                }
                if (instruction.isNotEmpty()) stepsList.add(RouteStep(stepGeo, instruction))
            }
        }
        return@withContext OsrmResult(path, route.getDouble("distance"), stepsList)
    } catch (e: Exception) { e.printStackTrace() }
    return@withContext null
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
fun getPointAtAngle(center: GeoPoint, dist: Double, angle: Double): GeoPoint {
    val rad = 111300.0
    val dLat = (dist * cos(Math.toRadians(angle))) / rad
    val dLon = (dist * sin(Math.toRadians(angle))) / (rad * cos(Math.toRadians(center.latitude)))
    return GeoPoint(center.latitude + dLat, center.longitude + dLon)
}
fun loadVisitedPoints(context: android.content.Context): List<GeoPoint> {
    val json = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getString("visited_points", null) ?: return emptyList()
    return try {
        val list = Gson().fromJson<List<Map<String, Double>>>(json, object : TypeToken<List<Map<String, Double>>>() {}.type)
        list.map { GeoPoint(it["lat"]!!, it["lon"]!!) }
    } catch (e: Exception) { emptyList() }
}