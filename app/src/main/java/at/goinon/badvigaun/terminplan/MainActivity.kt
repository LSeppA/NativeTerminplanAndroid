package at.goinon.badvigaun.terminplan

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(model::scan) }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success -> if (success) model.cameraUri?.let(model::scan) }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) openCamera() else model.showMessage("Kamerazugriff wurde nicht erlaubt.") }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) model.refreshStatusNotification() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { MaterialTheme { Screen() } }
        if (model.infoAccepted && Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun openCamera() {
        val file = File(cacheDir, "captures/plan-${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        model.cameraUri = FileProvider.getUriForFile(this, "$packageName.files", file)
        camera.launch(requireNotNull(model.cameraUri))
    }

    @Composable private fun Screen() {
        val schedule = model.schedule
        val busy = model.busy
        val infoAccepted = model.infoAccepted
        val groups = schedule.groupBy { it.date }.toSortedMap()
        var now by remember(schedule) { mutableLongStateOf(System.currentTimeMillis()) }
        var selectedPhotoPath by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(schedule) {
            while (true) {
                now = System.currentTimeMillis()
                val nextChange = nextAppointmentDisplayChange(schedule, now)
                delay(nextChange?.minus(now)?.coerceAtLeast(1_000L) ?: 60_000L)
            }
        }
        val next = appointmentForDisplay(schedule, now)
        val overviewBackground = Color(0xFFF4F4F9)
        val accentBlue = Color(0xFF0A84FF)
        val headingGray = Color(0xFF8E8E93)
        Scaffold(
            containerColor = overviewBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color.White, shadowElevation = 5.dp) {
                        IconButton(onClick = model::openClearConfirmation, enabled = schedule.isNotEmpty() && !busy) {
                            Icon(Icons.Filled.Delete, contentDescription = "Alle Termine löschen")
                        }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text("Mein Terminplan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = model::openInfo, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Info, contentDescription = "Informationen anzeigen", tint = accentBlue)
                        }
                    }
                    Surface(shape = CircleShape, color = Color.White, shadowElevation = 5.dp) {
                        IconButton(onClick = model::openImport, enabled = infoAccepted && !busy) {
                            Icon(Icons.Filled.DocumentScanner, contentDescription = "Terminplan einlesen")
                        }
                    }
                }
                    if (!infoAccepted) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "Zum Einlesen bitte zuerst akzeptieren",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        ) { padding ->
            if (busy) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (schedule.isEmpty()) Text("Noch keine Termine. Mit dem Scan-Symbol oben rechts den Wochenplan fotografieren oder ein Bild auswählen.", Modifier.padding(padding).padding(24.dp))
            else LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                if (next != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val isCurrent = isCurrentAppointment(next, now)
                            Text(if (isCurrent) "Jetzt" else "Demnächst", color = headingGray, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            NextAppointmentCard(next, accentBlue, concurrentAppointmentCount(schedule, next), isCurrent) { selectedPhotoPath = it }
                        }
                    }
                }
                groups.forEach { (date, entries) ->
                    item(key = date) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.forLanguageTag("de-AT")).format(Date(date)),
                                color = headingGray,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(color = Color.White, shape = RoundedCornerShape(28.dp)) {
                                Column {
                                    val sortedEntries = entries.sortedBy { it.start ?: Int.MAX_VALUE }
                                    sortedEntries.forEachIndexed { index, item ->
                                        AppointmentRow(item) { selectedPhotoPath = it }
                                        if (index < sortedEntries.lastIndex) {
                                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Color(0xFFE5E5EA))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (model.showCaptureHint) {
            Box(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 76.dp, start = 20.dp, end = 20.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shadowElevation = 6.dp) {
                    Text(
                        "Bitte prüfe die erfassten Daten mit den Terminen auf dem Papier. Falls Abweichungen bestehen, erstelle bitte eine neue Aufnahme direkt über dem Papier.",
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (model.showImport) AlertDialog(onDismissRequest = model::dismissImport, title = { Text("Terminplan einlesen") }, text = { Column {
            TextButton(onClick = { model.dismissImport(); cameraPermission.launch(Manifest.permission.CAMERA) }) { Text("Kamera") }
            TextButton(onClick = { model.dismissImport(); gallery.launch("image/*") }) { Text("Fotomediathek") }
        } }, confirmButton = { TextButton(onClick = model::dismissImport) { Text("Abbrechen") } })
        model.importReview?.let { items ->
            Dialog(onDismissRequest = model::discardImport) {
                Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f), shape = RoundedCornerShape(24.dp)) {
                    Column {
                        Text(
                            "Erkannte Termine",
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)
                        ) {
                            items(items.size) { index ->
                                val item = items[index]
                                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (item.timeText.isNotEmpty()) Text(item.timeText, color = Color(0xFF0A84FF), fontWeight = FontWeight.SemiBold)
                                        Text(if (item.information) "Info" else "Termin", color = Color(0xFF8E8E93), style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(item.title, fontWeight = if (item.information) FontWeight.Normal else FontWeight.SemiBold)
                                    item.duration?.let { Text("$it Minuten", color = Color(0xFF8E8E93), style = MaterialTheme.typography.bodySmall) }
                                    if (item.note.isNotEmpty()) Text(item.note, color = Color(0xFF8E8E93), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                                }
                                if (index < items.lastIndex) HorizontalDivider(color = Color(0xFFE5E5EA))
                            }
                        }
                        Text(
                            "Bitte prüfe die erkannten Termine mit dem Papierplan. Wenn etwas nicht stimmt, verwirf den Import und scanne den Plan erneut.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            color = Color(0xFF8E8E93),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            TextButton(onClick = model::discardImport) { Text("Verwerfen", color = MaterialTheme.colorScheme.error) }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = model::commitImport) { Text("Übernehmen") }
                        }
                    }
                }
            }
        }
        if (model.showClear) AlertDialog(onDismissRequest = model::dismissClearConfirmation, title = { Text("Gespeicherte Termine löschen?") }, confirmButton = { TextButton(onClick = model::clearSchedule) { Text("Alle Termine löschen") } }, dismissButton = { TextButton(onClick = model::dismissClearConfirmation) { Text("Abbrechen") } })
        if (model.showInfo) Dialog(onDismissRequest = model::dismissInfo) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        if (!infoAccepted) {
                            TextButton(onClick = {
                                model.declineInfo()
                            }) { Text("Nicht akzeptieren") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            val newlyAccepted = !model.infoAccepted
                            model.acceptInfo()
                            if (newlyAccepted) {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }) { Text(if (infoAccepted) "Fertig" else "Akzeptieren") }
                    }
                    AndroidView(factory = { context -> WebView(context).apply {
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return false
                                if (uri.scheme != "mailto") return false
                                runCatching { startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
                                    .onFailure { model.showMessage("Keine E-Mail-App verfügbar.") }
                                return true
                            }
                        }
                        loadUrl("file:///android_asset/Info.html")
                    } }, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
        selectedPhotoPath?.let { path -> PhotoViewer(path) { selectedPhotoPath = null } }
        model.message?.let { msg -> AlertDialog(onDismissRequest = model::dismissMessage, title = { Text("Hinweis") }, text = { Text(msg) }, confirmButton = { TextButton(onClick = model::dismissMessage) { Text("OK") } }) }
    }

    @Composable private fun NextAppointmentCard(item: ScheduleItem, accentBlue: Color, concurrentCount: Int, isCurrent: Boolean, onShowPhoto: (String) -> Unit) {
        Surface(color = Color.White, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = accentBlue) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
                        }
                    }
                    Text(item.timeText, color = accentBlue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (concurrentCount > 1) {
                        Surface(shape = CircleShape, color = Color.Red) {
                            Text("$concurrentCount", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                    if (isCurrent) {
                        Surface(shape = RoundedCornerShape(12.dp), color = accentBlue.copy(alpha = 0.15f)) {
                            Text("Jetzt", color = accentBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    item.photoPath?.let { path ->
                        IconButton(onClick = { onShowPhoto(path) }) { Icon(Icons.Filled.Photo, contentDescription = "Foto anzeigen", tint = Color(0xFF8E8E93)) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (item.location.isNotEmpty()) Text(item.location, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (item.note.isNotEmpty()) Text(item.note, color = Color(0xFF8E8E93), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }
        }
    }

    @Composable private fun AppointmentRow(item: ScheduleItem, onShowPhoto: (String) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (item.timeText.isNotEmpty()) {
                Column(Modifier.width(108.dp)) {
                    Text(item.timeText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    item.duration?.let { Text("$it Minuten", color = Color(0xFF8E8E93), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (item.location.isNotEmpty()) Text(item.location, style = MaterialTheme.typography.bodyLarge)
                if (item.note.isNotEmpty()) Text(item.note, color = Color(0xFF8E8E93), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }
            item.photoPath?.let { path ->
                IconButton(onClick = { onShowPhoto(path) }) { Icon(Icons.Filled.Photo, contentDescription = "Foto anzeigen", tint = Color(0xFF8E8E93)) }
            }
        }
    }

    @Composable private fun PhotoViewer(path: String, onClose: () -> Unit) {
        val bitmap by produceState<Bitmap?>(initialValue = null, path) {
            value = withContext(Dispatchers.IO) { decodePhoto(path) }
        }
        var scale by remember(path) { mutableFloatStateOf(1f) }
        var offset by remember(path) { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { _, zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 4f)
            offset = if (scale > 1f) offset + pan else Offset.Zero
        }
        Dialog(onDismissRequest = onClose) {
            Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.95f), shape = RoundedCornerShape(20.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Foto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        TextButton(onClick = onClose) { Text("Fertig") }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        if (bitmap == null) CircularProgressIndicator()
                        else Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "Foto des eingelesenen Terminplans",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                                .transformable(transformState)
                                .pointerInput(path) {
                                    detectTapGestures(onDoubleTap = {
                                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2f
                                    })
                                }
                        )
                    }
                }
            }
        }
    }
}

private fun decodePhoto(path: String, maxEdge: Int = 2600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}
