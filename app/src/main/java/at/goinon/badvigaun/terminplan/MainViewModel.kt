package at.goinon.badvigaun.terminplan

import android.app.Application
import android.net.Uri
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScheduleStore(application)
    private val scanner = PlanScanner(application)
    private val photoStore = PhotoStore(application)
    private val preferences = application.getSharedPreferences("app_preferences", 0)

    var schedule by mutableStateOf(store.load())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var showInfo by mutableStateOf(false)
        private set
    var showClear by mutableStateOf(false)
        private set
    var showImport by mutableStateOf(false)
        private set
    var importReview by mutableStateOf<List<ScheduleItem>?>(null)
        private set
    var showCaptureHint by mutableStateOf(false)
        private set
    var infoAccepted by mutableStateOf(preferences.getBoolean("info_accepted", false))
        private set
    var cameraUri: Uri? = null

    init {
        showInfo = !infoAccepted
        photoStore.deleteUnreferenced(schedule)
        Reminders.sync(application, schedule)
        StatusNotification.sync(application, schedule)
    }

    fun scan(uri: Uri) {
        if (busy) return
        importReview = null
        busy = true
        viewModelScope.launch {
            try {
                val parsed = scanner.scan(uri)
                if (parsed.isEmpty()) {
                    message = "Es wurden keine Termine oder Informationszeilen erkannt. Bitte erneut fotografieren."
                } else {
                    val now = System.currentTimeMillis()
                    val activeItems = parsed.filter { it.information || (it.endTime ?: 0L) >= now }
                    if (activeItems.isEmpty()) {
                        message = "Es wurden keine zukünftigen Termine im Plan erkannt."
                        return@launch
                    }
                    val photoPath = photoStore.save(uri)
                    importReview = activeItems.map { item ->
                        if (item.information) item else item.copy(photoPath = photoPath)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.localizedMessage ?: "Das Bild konnte nicht verarbeitet werden."
            } finally {
                busy = false
            }
        }
    }

    fun commitImport() {
        val imported = importReview ?: return
        Reminders.cancelAll(getApplication())
        schedule = mergeScannedDays(store.load(), imported)
        store.save(schedule)
        photoStore.deleteUnreferenced(schedule)
        Reminders.sync(getApplication(), schedule)
        importReview = null
        showCaptureHint = true
        viewModelScope.launch {
            delay(3_000)
            showCaptureHint = false
        }
    }

    fun discardImport() {
        importReview = null
        photoStore.deleteUnreferenced(schedule)
    }

    fun clearSchedule() {
        if (busy) return
        Reminders.cancelAll(getApplication())
        store.clear()
        photoStore.deleteAll()
        schedule = emptyList()
        importReview = null
        showCaptureHint = false
        showClear = false
    }

    fun acceptInfo() {
        if (!infoAccepted) {
            preferences.edit { putBoolean("info_accepted", true) }
            infoAccepted = true
        }
        showInfo = false
    }

    fun dismissInfo() { if (infoAccepted) showInfo = false }
    fun declineInfo() { showInfo = false }
    fun openInfo() { showInfo = true }
    fun openClearConfirmation() { if (!busy) showClear = true }
    fun dismissClearConfirmation() { showClear = false }
    fun openImport() { if (infoAccepted && !busy) showImport = true }
    fun dismissImport() { showImport = false }
    fun dismissMessage() { message = null }
    fun showMessage(text: String) { message = text }
    fun refreshStatusNotification() = StatusNotification.sync(getApplication(), schedule)

    override fun onCleared() {
        scanner.close()
    }
}
