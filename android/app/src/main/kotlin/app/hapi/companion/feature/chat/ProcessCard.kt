package app.hapi.companion.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import kotlinx.coroutines.delay

/** A text disclosure, deliberately not a Surface/card. */
@Composable
internal fun ProcessCard(process: TranscriptItem.Process, expanded: Boolean, working: Boolean, onToggle: () -> Unit) {
    val disclosure = stringResource(if (expanded) R.string.chat_process_expanded else R.string.chat_process_collapsed)
    Row(
        modifier = Modifier.fillMaxWidth().testTag("chat-process-toggle-${process.id}")
            .semantics { stateDescription = disclosure }
            .clickable(role = Role.Button, onClick = onToggle).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (process.finished) workingLabel(false, process.durationMs)
            else stringResource(R.string.chat_process_summary, process.summary.operations, process.summary.thoughts),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(if (expanded) "⌄" else "›", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (working) Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (process.summary.errors > 0) Text(stringResource(R.string.chat_process_errors, process.summary.errors),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun WorkingStatus(startedAt: Long?) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    Text(workingLabel(true, startedAt?.let { (now - it).coerceAtLeast(0) }),
        modifier = Modifier.padding(vertical = 6.dp).testTag("chat-working-status"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
