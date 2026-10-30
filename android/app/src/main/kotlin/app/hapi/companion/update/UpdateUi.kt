package app.hapi.companion.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hapi.companion.BuildConfig
import app.hapi.companion.R
import kotlinx.coroutines.launch

@Composable
fun UpdateSettings(updater: AppUpdater) {
    val state by updater.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.update_automatic), Modifier.weight(1f))
                    Switch(checked = state.automatic, onCheckedChange = updater::setAutomatic)
                }
                Text(stringResource(R.string.update_source), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.update_current, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                TextButton(onClick = updater::check, enabled = state.check != UpdateCheck.CHECKING && state.downloaded == null) {
                    Text(stringResource(if (state.check == UpdateCheck.CHECKING) R.string.update_checking else R.string.update_check))
                }
                if (state.release != null) {
                    Text(stringResource(R.string.update_available, state.release!!.name))
                    UpdateActions(updater, state)
                } else when (state.check) {
                    UpdateCheck.CURRENT -> Text(stringResource(R.string.update_latest))
                    UpdateCheck.NO_RELEASE -> Text(stringResource(R.string.update_no_release))
                    else -> Unit
                }
                UpdateErrorText(state.error)
            }
        }
    }
}

@Composable
fun UpdatePrompt(updater: AppUpdater) {
    if (!BuildConfig.SELF_UPDATE_ENABLED) return
    val state by updater.state.collectAsState()
    if (!state.prompt || state.release == null) return
    AlertDialog(
        onDismissRequest = updater::dismiss,
        title = { Text(stringResource(R.string.update_available, state.release!!.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_source))
                UpdateErrorText(state.error)
            }
        },
        confirmButton = { UpdateActions(updater, state) },
        dismissButton = {
            TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.update_later)) }
        },
    )
}

@Composable
private fun UpdateActions(updater: AppUpdater, state: UpdateState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCode by rememberSaveable { mutableStateOf<Int?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val install: (Int) -> Unit = { code ->
        scope.launch {
            preparing = true
            try {
                updater.installIntent(code)?.let { context.startActivity(it) }
            } catch (_: Exception) {
                updater.error(UpdateError.INSTALL)
            } finally { preparing = false }
        }
    }
    val sourcePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val code = pendingCode
        pendingCode = null
        if (code != null && context.packageManager.canRequestPackageInstalls()) install(code)
        else updater.error(UpdateError.PERMISSION)
    }
    when {
        state.downloaded != null -> Column {
            val total = state.release?.size?.toFloat() ?: 1f
            LinearProgressIndicator(progress = { (state.downloaded / total).coerceIn(0f, 1f) })
            Text(stringResource(R.string.update_downloading, (state.downloaded * 100 / total).toInt()),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = updater::cancelDownload) { Text(stringResource(R.string.update_cancel)) }
        }
        state.ready -> Button(enabled = !preparing && state.check != UpdateCheck.CHECKING, onClick = {
            val code = state.release?.code ?: return@Button
            if (context.packageManager.canRequestPackageInstalls()) install(code)
            else {
                pendingCode = code
                try {
                    sourcePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                    pendingCode = null
                    updater.error(UpdateError.PERMISSION)
                }
            }
        }) { Text(stringResource(R.string.update_install)) }
        else -> Button(onClick = updater::download, enabled = state.check != UpdateCheck.CHECKING) {
            Text(stringResource(R.string.update_download))
        }
    }
}

@Composable
private fun UpdateErrorText(error: UpdateError?) {
    val text = when (error) {
        UpdateError.CHECK -> R.string.update_error_check
        UpdateError.DOWNLOAD -> R.string.update_error_download
        UpdateError.INVALID_APK -> R.string.update_error_apk
        UpdateError.INSTALL -> R.string.update_error_install
        UpdateError.PERMISSION -> R.string.update_error_permission
        null -> return
    }
    Text(stringResource(text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
