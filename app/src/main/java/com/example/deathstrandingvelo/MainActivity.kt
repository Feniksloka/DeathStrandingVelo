package com.example.deathstrandingvelo

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import androidx.compose.material.icons.filled.Settings
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import androidx.compose.material.icons.filled.Navigation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

data class CargoTemplate(
    val name: String,
    val description: String,
    val weightKg: Double,
    val isFragile: Boolean
)

// 1. СТРУКТУРА ДАННЫХ ДЛЯ ГРУЗА
enum class CargoStatus { PENDING, COLLECTED, CANCELED }

data class CargoItem(
    val id: Int,
    val name: String,
    val description: String,
    val weightKg: Double,
    val isFragile: Boolean,
    val location: GeoPoint,
    var status: CargoStatus = CargoStatus.PENDING
)
// Класс для Зоны покатушек
data class RideZone(val name: String, val center: GeoPoint, val radiusMeters: Double)
// Класс для хранения маневров (поворотов)
data class RouteStep(val location: GeoPoint, val instruction: String, var isSpoken: Boolean = false)

// Обновленный результат (теперь сервер будет возвращать еще и шаги)
data class OsrmResult(val path: List<GeoPoint>, val distanceMeters: Double, val steps: List<RouteStep>)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val locationPermissionsState = rememberMultiplePermissionsState(
                        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                    )
                    if (locationPermissionsState.allPermissionsGranted) {
                        MapScreen()
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Для сканера нужен GPS.")
                            Button(onClick = { locationPermissionsState.launchMultiplePermissionRequest() }) {
                                Text("Активировать")
                            }
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
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
    }
    // Отслеживаем готовность
    var isTtsReady by remember { mutableStateOf(false) }

    // БОЛЬШЕ НИКАКИХ ЖЕСТКИХ ПРИВЯЗОК (Убрали "com.samsung.SMT")
    // Пусть Android использует то, что выбрано в настройках телефона
    val tts = remember {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
            }
        }
    }

    // Как только загрузился — ищем приятную женщину
    LaunchedEffect(isTtsReady) {
        if (isTtsReady) {
            tts.language = Locale("ru")
            tts.setPitch(0.9f)

            // Слушатель: управляем громкостью внешних плееров в реальном времени
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Жестко приглушаем музыку (Ducking)
                    audioManager.requestAudioFocus(focusRequest)
                }
                override fun onDone(utteranceId: String?) {
                    // Возвращаем музыку в исходную громкость
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }
                override fun onError(utteranceId: String?) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }
            })
        }
    }

    var visitedPoints by remember { mutableStateOf<List<GeoPoint>>(loadVisitedPoints(context)) } // ИСТОРИЯ ДОСТАВОК


    var isFollowMode by remember { mutableStateOf(false) } // Включен ли режим "Навигатор"
    var currentBearing by remember { mutableStateOf(0f) } // Куда мы сейчас повернуты
    var shouldInitCamera by remember { mutableStateOf(false) } // Флаг для мгновенного зума при старте

    var userPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var cargoList by remember { mutableStateOf<List<CargoItem>>(emptyList()) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

    var navSteps by remember { mutableStateOf<List<RouteStep>>(emptyList()) } // Шаги навигатора
    var lastOffRouteWarningTime by remember { mutableStateOf(0L) } // Таймер для защиты от спама "вы сбились с пути"
    // Мониторинг и UI
    var totalDistanceMeters by remember { mutableStateOf(0.0) }
    var distanceTraveledMeters by remember { mutableStateOf(0.0) }
    var previousLocation by remember { mutableStateOf<android.location.Location?>(null) }

    var selectedMinutes by remember { mutableStateOf(60) }
    var isRouteBuilt by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var hasZoomedToRoute by remember { mutableStateOf(false) }

    // Состояния для диалогов (список и карточка груза)
    var showListDialog by remember { mutableStateOf(false) }
    var selectedCargo by remember { mutableStateOf<CargoItem?>(null) }

    // --- ПЕРЕМЕННЫЕ ДЛЯ ЗОН ПОКАТУШЕК ---
    // --- ПЕРЕМЕННЫЕ ДЛЯ ЗОН ПОКАТУШЕК ---
    var savedZones by remember { mutableStateOf<List<RideZone>>(loadZones(context)) } // Теперь грузим из памяти!
    var showSettingsDialog by remember { mutableStateOf(false) } // Окно настроек

    var selectedZone by remember { mutableStateOf<RideZone?>(null) }
    var showZoneDialog by remember { mutableStateOf<GeoPoint?>(null) }
    var newZoneRadius by remember { mutableStateOf(2000f) } // По умолчанию 2 км
    var newZoneName by remember { mutableStateOf("") }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    Configuration.getInstance().userAgentValue = context.packageName

    // Функция для анонса следующего груза
    val announceNext = {
        val nextCargo = cargoList.firstOrNull { it.status == CargoStatus.PENDING }
        if (nextCargo != null) {
            val fragileWarning = if (nextCargo.isFragile) "Внимание, груз хрупкий." else ""
            tts.speak(
                "Следующая цель: ${nextCargo.name}. ${nextCargo.description}. $fragileWarning",
                TextToSpeech.QUEUE_FLUSH, null, null
            )
        } else {
            tts.speak("Все грузы обработаны. Заказ выполнен, возвращайтесь на базу.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // Функция обработки груза (собрали или отменили)
    val processCargo = { cargoId: Int, newStatus: CargoStatus ->
        val targetCargo = cargoList.find { it.id == cargoId }
        cargoList = cargoList.map { if (it.id == cargoId) it.copy(status = newStatus) else it }

        if (newStatus == CargoStatus.COLLECTED) {
            tts.speak("Груз собран.", TextToSpeech.QUEUE_FLUSH, null, "nav")
            // ДОБАВЛЯЕМ ТОЧКУ В ИСТОРИЮ
            if (targetCargo != null) {
                val newHistory = visitedPoints + targetCargo.location
                visitedPoints = newHistory
                saveVisitedPoints(context, newHistory)
            }
        }
        else if (newStatus == CargoStatus.CANCELED) {
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

                    // Обработчик долгих нажатий по карте для создания зон
                    val mapEventsReceiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null && !isRouteBuilt) {
                                post {
                                    newZoneName = "Зона ${savedZones.size + 1}"
                                    showZoneDialog = p
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

                    // Слушатель GPS (Геофенсинг)
                    // Слушатель GPS (Геофенсинг)
                    myLocProvider.startLocationProvider(object : IMyLocationConsumer {
                        override fun onLocationChanged(loc: android.location.Location?, source: IMyLocationProvider?) {
                            if (loc != null) {
                                post {
                                    val currentGeo = GeoPoint(loc.latitude, loc.longitude)
                                    userPosition = currentGeo

                                    // ЕСЛИ GPS ПЕРЕДАЕТ НАПРАВЛЕНИЕ ДВИЖЕНИЯ — ЗАПОМИНАЕМ ЕГО
                                    if (loc.hasBearing()) {
                                        currentBearing = loc.bearing
                                    }

                                    if (isRouteBuilt) {
                                        // Считаем дистанцию
                                        if (previousLocation != null) {
                                            distanceTraveledMeters += previousLocation!!.distanceTo(loc)
                                            // 1. ПРОВЕРКА ОТКЛОНЕНИЯ ОТ МАРШРУТА
                                            var minDistanceToLine = Double.MAX_VALUE
                                            for (pt in routePoints) {
                                                val d = currentGeo.distanceToAsDouble(pt)
                                                if (d < minDistanceToLine) minDistanceToLine = d
                                            }

                                            // Если отъехали дальше 70 метров от линии
                                            if (minDistanceToLine > 70.0) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastOffRouteWarningTime > 20000) { // Не спамим (раз в 20 сек)
                                                    tts.speak("Внимание! Вы сбились с маршрута.", TextToSpeech.QUEUE_FLUSH, null, "nav")
                                                    lastOffRouteWarningTime = now
                                                }
                                            }

                                            // 2. TURN-BY-TURN НАВИГАЦИЯ
                                            val nextStep = navSteps.firstOrNull { !it.isSpoken }
                                            if (nextStep != null) {
                                                val distToTurn = currentGeo.distanceToAsDouble(nextStep.location)

                                                // Если до поворота 20-120 метров — говорим
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
                                                    // Проехали поворот, помечаем как "сказанный"
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

                                        // ГЕОФЕНСИНГ: Проверяем радиус 25 метров
                                        val nextCargo = cargoList.firstOrNull { it.status == CargoStatus.PENDING }
                                        if (nextCargo != null) {
                                            val distToCargo = currentGeo.distanceToAsDouble(nextCargo.location)
                                            if (distToCargo <= 25.0) {
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
                // ЭТОТ БЛОК УПРАВЛЯЕТ КАМЕРОЙ НАВИГАТОРА
                if (isFollowMode && userPosition != null) {
                    mapView.controller.animateTo(userPosition)
                    mapView.mapOrientation = -currentBearing // Поворот карты против угла движения
                } else if (!isFollowMode) {
                    mapView.mapOrientation = 0f // Возвращаем север наверх, если режим выключен
                }

                if (shouldInitCamera) {
                    shouldInitCamera = false
                    mapView.controller.setZoom(18.0) // Приближаем вплотную к игроку
                    if (userPosition != null) mapView.controller.animateTo(userPosition)
                }

                mapView.overlays.removeAll { it is Marker || it is Polyline || it is Polygon || it is MapEventsOverlay }

                // Рисуем зоны
                savedZones.forEach { zone ->
                    val zonePolygon = Polygon(mapView).apply {
                        points = Polygon.pointsAsCircle(zone.center, zone.radiusMeters)
                        fillPaint.color = android.graphics.Color.parseColor("#330088FF")
                        outlinePaint.color = android.graphics.Color.parseColor("#0088FF")
                        outlinePaint.strokeWidth = 3f
                    }
                    mapView.overlays.add(zonePolygon)
                }

                // --- 1. ТРЕХЦВЕТНАЯ ОТРИСОВКА ЛИНИИ МАРШРУТА ---
                if (routePoints.isNotEmpty()) {
                    var closestUserIndex = 0

                    // Ищем ближайшую к нам точку на всем маршруте (где мы сейчас)
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

                    // Ищем точку на линии маршрута, где лежит следующий груз
                    if (nextCargo != null) {
                        var minDtCargo = Double.MAX_VALUE
                        // Ищем вперед от текущего положения, чтобы не запутаться в петлях маршрута
                        for (i in closestUserIndex until routePoints.size) {
                            val d = routePoints[i].distanceToAsDouble(nextCargo.location)
                            if (d < minDtCargo) {
                                minDtCargo = d
                                nextCargoIndex = i
                            }
                        }
                    }

                    // А. ПРОЙДЕННЫЙ ПУТЬ (Серый)
                    if (closestUserIndex > 0) {
                        val traveledLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(0, closestUserIndex + 1))
                            outlinePaint.color = android.graphics.Color.parseColor("#888888") // Серый
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(traveledLine)
                    }

                    // Б. ТЕКУЩИЙ УЧАСТОК ДО БЛИЖАЙШЕГО ГРУЗА (Ярко-зеленый)
                    if (closestUserIndex < nextCargoIndex) {
                        val currentLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(closestUserIndex, nextCargoIndex + 1))
                            outlinePaint.color = android.graphics.Color.parseColor("#00FF00") // Зеленый
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(currentLine)
                    }

                    // В. ОСТАВШИЙСЯ ПУТЬ КО ВСЕМ СЛЕДУЮЩИМ ГРУЗАМ (Оранжевый)
                    if (nextCargoIndex < routePoints.size - 1) {
                        val futureLine = Polyline(mapView).apply {
                            setPoints(routePoints.subList(nextCargoIndex, routePoints.size))
                            outlinePaint.color = android.graphics.Color.parseColor("#FFA500") // Оранжевый
                            outlinePaint.strokeWidth = 12f
                        }
                        mapView.overlays.add(futureLine)
                    }

                    if (!hasZoomedToRoute) {
                        hasZoomedToRoute = true
                        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(routePoints), true, 150)
                    }
                }

                // --- 2. ОТРИСОВКА ГРУЗОВ С ЦИФРАМИ ---
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
                            icon = createCustomMarker(context, cargo.id, mainColor) // Используем нашу функцию рисования кругов с цифрами
                            setOnMarkerClickListener { _, _ ->
                                selectedCargo = cargo
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }


// РИСУЕМ СТРЕЛОЧКУ НАВИГАТОРА НАД НАШЕЙ ПОЗИЦИЕЙ
                if (userPosition != null) {
                    val userArrowMarker = Marker(mapView).apply {
                        position = userPosition
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = createUserArrowMarker(context)
                        setFlat(false) // Важно! Стрелка всегда будет смотреть строго ВВЕРХ экрана
                    }
                    mapView.overlays.add(userArrowMarker)
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // КНОПКА СПИСКА ГРУЗОВ (Появляется, когда построен маршрут)
        // КНОПКА ПОЛНОЦЕННОГО НАВИГАТОРА (Следование и автоповорот)
        if (isRouteBuilt) {
            FloatingActionButton(
                onClick = {
                    isFollowMode = !isFollowMode
                    if (isFollowMode) shouldInitCamera = true
                },
                containerColor = if (isFollowMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, bottom = 190.dp) // Кнопка встанет ровно над кнопкой списка
            ) {
                Icon(Icons.Filled.Navigation, contentDescription = "Режим навигации")
            }
        }

        // ТОП ПАНЕЛЬ: Генерация заказа
        if (userPosition != null && !isRouteBuilt) {
            Card(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth(0.9f)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Заголовок и кнопка настроек
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text("Генерация заказа", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 8.dp, y = (-8).dp)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 90).forEach { mins ->
                            FilterChip(
                                selected = selectedMinutes == mins,
                                onClick = { selectedMinutes = mins },
                                label = { Text("$mins мин") }
                            )
                        }
                    }
                    // ВЫПАДАЮЩЕЕ МЕНЮ ВЫБОРА ЗОНЫ
                    if (savedZones.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ExposedDropdownMenuBox(
                            expanded = isDropdownExpanded,
                            onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedZone?.name ?: "Вокруг меня (Случайно)",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Где катаемся?") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = isDropdownExpanded, onDismissRequest = { isDropdownExpanded = false }) {
                                DropdownMenuItem(text = { Text("Вокруг меня (Случайно)") }, onClick = { selectedZone = null; isDropdownExpanded = false })
                                savedZones.forEach { zone ->
                                    DropdownMenuItem(text = { Text(zone.name) }, onClick = { selectedZone = zone; isDropdownExpanded = false })
                                }
                            }
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
                                    generateSmartRoute(start, selectedMinutes, selectedZone, visitedPoints) { bestPoints, route, totalDistance, generatedSteps ->
                                        // 1. БЕЗОПАСНАЯ ЧТЕНИЕ БАЗЫ ДАННЫХ (Защита от вылетов)
                                        val allTemplates: List<CargoTemplate> = try {
                                            val jsonString = context.assets.open("cargo_db.json").bufferedReader().use { it.readText() }
                                            val templateType = object : TypeToken<List<CargoTemplate>>() {}.type
                                            Gson().fromJson(jsonString, templateType)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            // Если файла нет или в нем ошибка, отдаем резервный список
                                            listOf(
                                                CargoTemplate("Аварийный груз", "Файл базы данных не найден. Проверь папку assets.", 5.0, false),
                                                CargoTemplate("Утерянный контейнер", "Резервная генерация.", 2.5, true)
                                            )
                                        }

                                        // 2. Перемешиваем базу
                                        val shuffledTemplates = allTemplates.shuffled()

                                        // 3. Привязываем грузы (если точек больше чем грузов, они пойдут по кругу)
                                        cargoList = bestPoints.mapIndexed { index, pt ->
                                            val template = if (shuffledTemplates.isNotEmpty()) {
                                                shuffledTemplates[index % shuffledTemplates.size]
                                            } else {
                                                CargoTemplate("Пусто", "Пусто", 0.0, false)
                                            }

                                            CargoItem(
                                                id = index + 1,
                                                name = template.name,
                                                description = template.description,
                                                weightKg = template.weightKg,
                                                isFragile = template.isFragile,
                                                location = pt
                                            )
                                        }

                                        routePoints = route
                                        navSteps = generatedSteps
                                        totalDistanceMeters = totalDistance
                                        distanceTraveledMeters = 0.0
                                        previousLocation = null

                                        // --- НАСТРОЙКА НАВИГАТОРА ПО УМОЛЧАНИЮ ---
                                        isFollowMode = true
                                        shouldInitCamera = true
                                        hasZoomedToRoute = true // Отключаем старый зум на всю карту

                                        // Разворачиваем карту к самой первой точке маршрута сразу!
                                        val firstPt = route.firstOrNull()
                                        if (start != null && firstPt != null) {
                                            currentBearing = getBearingBetween(start, firstPt)
                                        }
                                        // ----------------------------------------

                                        isRouteBuilt = true
                                        isLoading = false

                                        val tKm = ((totalDistance / 1000.0) * 10).roundToInt() / 10.0

                                        // Вытаскиваем самый первый поворот маршрута
                                        val firstInstruction = generatedSteps.firstOrNull()?.instruction ?: "следуйте по маршруту"

                                        // Четвертый параметр "nav" ОБЯЗАТЕЛЕН для приглушения музыки!
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
    }

    // ДИАЛОГ: Список всех грузов
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
                                .clickable {
                                    showListDialog = false
                                    selectedCargo = cargo
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = when (cargo.status) {
                                    CargoStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                                    CargoStatus.COLLECTED -> androidx.compose.ui.graphics.Color(0xFFCCFFCC)
                                    CargoStatus.CANCELED -> androidx.compose.ui.graphics.Color(0xFFFFCCCC)
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(cargo.name, style = MaterialTheme.typography.titleSmall)
                                Text("Статус: ${cargo.status}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showListDialog = false }) { Text("Закрыть") }
            }
        )
    }

    // ДИАЛОГ: Детали конкретного груза (Ручное управление)
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
                    Button(onClick = {
                        processCargo(cargo.id, CargoStatus.COLLECTED)
                        selectedCargo = null
                    }) { Text("Собрать") }
                }
            },
            dismissButton = {
                if (cargo.status == CargoStatus.PENDING) {
                    OutlinedButton(onClick = {
                        processCargo(cargo.id, CargoStatus.CANCELED)
                        selectedCargo = null
                    }) { Text("Отменить (Скип)") }
                } else {
                    TextButton(onClick = { selectedCargo = null }) { Text("Закрыть") }
                }
            }
        )
    }
    // ДИАЛОГ: Создание новой зоны
    if (showZoneDialog != null) {
        AlertDialog(
            onDismissRequest = { showZoneDialog = null },
            title = { Text("Создать зону покатушек") },
            text = {
                Column {
                    OutlinedTextField(value = newZoneName, onValueChange = { newZoneName = it }, label = { Text("Название зоны") })
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Радиус: ${(newZoneRadius / 1000).roundToInt()} км", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = newZoneRadius,
                        onValueChange = { newZoneRadius = it },
                        valueRange = 1000f..10000f // От 1 до 10 км
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newZones = savedZones + RideZone(newZoneName, showZoneDialog!!, newZoneRadius.toDouble())
                    savedZones = newZones
                    saveZones(context, newZones) // Записываем в телефон!
                    showZoneDialog = null
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showZoneDialog = null }) { Text("Отмена") }
            }
        )
    }
    // ДИАЛОГ: Настройки и управление зонами
    // ДИАЛОГ: Настройки терминала
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Настройки терминала") },
            text = {
                Column {
                    // --- РАЗДЕЛ ЗОН ---
                    Text("Зоны доставки", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (userPosition != null) {
                                newZoneName = "Новая зона ${savedZones.size + 1}"
                                showZoneDialog = userPosition
                                showSettingsDialog = false
                            } else {
                                android.widget.Toast.makeText(context, "Ждем GPS...", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Создать зону вокруг меня") }

                    if (savedZones.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                            items(savedZones) { zone ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(zone.name)
                                    TextButton(onClick = {
                                        val newZ = savedZones.filter { it != zone }
                                        savedZones = newZ; saveZones(context, newZ)
                                        if (selectedZone == zone) selectedZone = null
                                    }) { Text("Удал.", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // --- РАЗДЕЛ СИСТЕМЫ ---
                    Text("Система (В разработке)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Уникальных доставок в базе: ${visitedPoints.size}")
                    Button(
                        onClick = {
                            visitedPoints = emptyList()
                            saveVisitedPoints(context, emptyList())
                            android.widget.Toast.makeText(context, "История очищена", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сбросить историю мест (500м)") }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Закрыть") } }
        )
    }
}

suspend fun generateSmartRoute(
    start: GeoPoint, minutes: Int, targetZone: RideZone?, visitedHistory: List<GeoPoint>,
    onResult: (List<GeoPoint>, List<GeoPoint>, Double, List<RouteStep>) -> Unit
) {
    val targetDist = (minutes / 60.0) * 12.0 * 1000.0
    var edges = (targetDist / 3000.0).roundToInt().coerceAtLeast(3)
    var curRadius = (targetDist / 1.3) / edges / (2 * sin(Math.PI / edges))
    var best: OsrmResult? = null; var bestPts: List<GeoPoint> = emptyList(); var minDiff = Double.MAX_VALUE

    // Увеличили попытки до 40, чтобы было больше шансов найти место вдали от старых точек
    for (attempt in 1..40) {
        val cAngle = Random.nextDouble(0.0, 360.0)
        val center = targetZone?.center ?: getPointAtAngle(start, curRadius, cAngle)
        val radForPts = targetZone?.radiusMeters ?: curRadius
        val attPts = mutableListOf<GeoPoint>()
        for (i in 0 until edges) attPts.add(getPointAtAngle(center, radForPts, cAngle + 180.0 + (i * (360.0 / edges))))

        // --- ПРОВЕРКА НА 500 МЕТРОВ ---
        var isTooClose = false
        // Строгий фильтр работает только первые 30 попыток (чтобы не зависнуть, если вся карта пройдена)
        if (attempt <= 30 && visitedHistory.isNotEmpty()) {
            for (pt in attPts) {
                if (visitedHistory.any { it.distanceToAsDouble(pt) < 500.0 }) {
                    isTooClose = true; break
                }
            }
        }
        if (isTooClose) continue // Точка слишком близко! Бросаем этот вариант и крутим рулетку дальше.

        attPts[0] = start; attPts.add(start)

        val res = fetchOSRMRoute(attPts)
        if (res != null) {
            val diff = abs(res.distanceMeters - targetDist)
            if (diff < minDiff) { minDiff = diff; best = res; bestPts = attPts }
            if (diff < targetDist * 0.15) break
            if (res.distanceMeters > targetDist * 1.3) curRadius *= 0.9
        }
    }
    if (best != null && best.distanceMeters <= targetDist * 1.4) onResult(bestPts.drop(1).dropLast(1), best.path, best.distanceMeters, best.steps)
    else onResult(emptyList(), emptyList(), 0.0, emptyList())
}


suspend fun fetchOSRMRoute(points: List<GeoPoint>): OsrmResult? = withContext(Dispatchers.IO) {
    try {
        val coords = points.joinToString(";") { "${it.longitude},${it.latitude}" }
        // Добавлен параметр &steps=true для запроса маневров поворотов
        val url = URL("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true")
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

fun getPointAtAngle(center: GeoPoint, dist: Double, angle: Double): GeoPoint {
    val rad = 111300.0
    val dLat = (dist * cos(Math.toRadians(angle))) / rad
    val dLon = (dist * sin(Math.toRadians(angle))) / (rad * cos(Math.toRadians(center.latitude)))
    return GeoPoint(center.latitude + dLat, center.longitude + dLon)
}// Вычисление направления между двумя точками (чтобы повернуть карту на старте)


fun getBearingBetween(p1: GeoPoint, p2: GeoPoint): Float {
    val loc1 = android.location.Location("").apply { latitude = p1.latitude; longitude = p1.longitude }
    val loc2 = android.location.Location("").apply { latitude = p2.latitude; longitude = p2.longitude }
    return loc1.bearingTo(loc2)
}

// Рисование кастомной стрелочки навигатора
fun createUserArrowMarker(context: android.content.Context): android.graphics.drawable.Drawable {
    val bitmap = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#007AFF") // Яркий навигационный синий
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    val path = android.graphics.Path().apply {
        moveTo(40f, 15f)   // Нос стрелки
        lineTo(65f, 65f)   // Правый хвост
        lineTo(40f, 50f)   // Внутренний балансир
        lineTo(15f, 65f)   // Левый хвост
        close()
    }
    canvas.drawPath(path, paint)
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

// --- ФУНКЦИИ ДЛЯ ПАМЯТИ ---
fun saveZones(context: android.content.Context, zones: List<RideZone>) {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    prefs.edit().putString("saved_zones", Gson().toJson(zones)).apply()
}

fun loadZones(context: android.content.Context): List<RideZone> {
    val prefs = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE)
    val json = prefs.getString("saved_zones", null) ?: return emptyList()
    return try {
        Gson().fromJson(json, object : TypeToken<List<RideZone>>() {}.type)
    } catch (e: Exception) { emptyList() }
}

// --- ГЕНЕРАТОР МАРКЕРОВ С ЦИФРАМИ ---
fun createCustomMarker(context: android.content.Context, number: Int, color: Int): android.graphics.drawable.Drawable {
    val bitmap = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        this.color = color
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    // Рисуем цветной круг
    canvas.drawCircle(40f, 40f, 35f, paint)

    // Рисуем белую цифру
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 40f
    paint.textAlign = android.graphics.Paint.Align.CENTER
    canvas.drawText(number.toString(), 40f, 53f, paint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

// --- ПАМЯТЬ ДЛЯ ИСТОРИИ ПОЕЗДОК (500м) ---
fun saveVisitedPoints(context: android.content.Context, points: List<GeoPoint>) {
    // Сохраняем координаты как простой список словарей
    val json = Gson().toJson(points.map { mapOf("lat" to it.latitude, "lon" to it.longitude) })
    context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).edit().putString("visited_points", json).apply()
}

fun loadVisitedPoints(context: android.content.Context): List<GeoPoint> {
    val json = context.getSharedPreferences("bike_prefs", android.content.Context.MODE_PRIVATE).getString("visited_points", null) ?: return emptyList()
    return try {
        val list = Gson().fromJson<List<Map<String, Double>>>(json, object : TypeToken<List<Map<String, Double>>>() {}.type)
        list.map { GeoPoint(it["lat"]!!, it["lon"]!!) }
    } catch (e: Exception) { emptyList() }
}