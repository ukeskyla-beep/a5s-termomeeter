package ee.ukesk.a5s.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TempUnit(val suffix: String) {
    CELSIUS("°C"),
    FAHRENHEIT("°F"),
    ;

    /** Kraadid tulevad andurilt alati Celsiuses — teisendus on ainult kuvamiseks. */
    fun from(celsius: Double): Double =
        if (this == CELSIUS) celsius else celsius * 9.0 / 5.0 + 32.0
}

/**
 * Väikesed püsivad eelistused. SharedPreferences, mitte DataStore — kaks välja
 * ei õigusta uut sõltuvust ega korutiinipõhist API-t.
 */
object Settings {

    private const val FILE = "a5s_settings"
    private const val KEY_UNIT = "temp_unit"
    private const val KEY_ALARM_SOUND = "alarm_sound_uri"

    private lateinit var prefs: SharedPreferences

    private val _unit = MutableStateFlow(TempUnit.CELSIUS)
    val unit: StateFlow<TempUnit> = _unit.asStateFlow()

    /** null = kasuta süsteemi vaikimisi äratushelinat. */
    private val _alarmSoundUri = MutableStateFlow<String?>(null)
    val alarmSoundUri: StateFlow<String?> = _alarmSoundUri.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _unit.value = runCatching {
            TempUnit.valueOf(prefs.getString(KEY_UNIT, null) ?: TempUnit.CELSIUS.name)
        }.getOrDefault(TempUnit.CELSIUS)
        _alarmSoundUri.value = prefs.getString(KEY_ALARM_SOUND, null)
    }

    fun setUnit(unit: TempUnit) {
        _unit.value = unit
        prefs.edit().putString(KEY_UNIT, unit.name).apply()
    }

    fun setAlarmSoundUri(uri: String?) {
        _alarmSoundUri.value = uri
        prefs.edit().putString(KEY_ALARM_SOUND, uri).apply()
    }
}
