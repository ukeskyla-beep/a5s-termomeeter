package ee.ukesk.a5s

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.ukesk.a5s.ble.ThermometerService
import ee.ukesk.a5s.data.Settings
import ee.ukesk.a5s.ui.A5sTheme
import ee.ukesk.a5s.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startTracking()
        }

    private val ringtoneLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.let {
                IntentCompat.getParcelableExtra(
                    it,
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java,
                )
            }
            Settings.setAlarmSoundUri(uri?.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        enableEdgeToEdge()

        setContent {
            val alarmUri by Settings.alarmSoundUri.collectAsStateWithLifecycle()
            val alarmLabel = remember(alarmUri) { ringtoneTitle(this, alarmUri) }

            A5sTheme {
                AppRoot(
                    onRequestPermissions = ::requestPermissions,
                    onPickAlarmSound = ::pickAlarmSound,
                    alarmSoundLabel = alarmLabel,
                )
            }
        }
    }

    private fun requestPermissions() {
        if (hasBlePermissions()) {
            startTracking()
            return
        }

        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    /**
     * Teenus käivitub ka ilma Bluetoothi loata — siis jääb ainult BLE-pool
     * seisma, aga demo sond, alarm ja ajalugu töötavad. Teenus ise valib
     * foreground service'i tüübi lubade järgi, nii et SecurityException'it
     * ei teki.
     */
    private fun startTracking() {
        ThermometerService.start(this)
    }

    private fun hasBlePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Androidi enda helinavalija — nii ei pea me helifaile ise haldama. */
    private fun pickAlarmSound() {
        val current = Settings.alarmSoundUri.value?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Vali alarmi helin")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        runCatching { ringtoneLauncher.launch(intent) }
    }
}

private fun ringtoneTitle(context: Context, uriString: String?): String {
    if (uriString == null) return "Süsteemi vaikimisi äratus"
    return runCatching {
        RingtoneManager.getRingtone(context, Uri.parse(uriString)).getTitle(context)
    }.getOrDefault("Süsteemi vaikimisi äratus")
}
