package app.hapi.companion.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.hapi.companion.R

@Composable
internal fun ProcessCard(
    process: TranscriptItem.Process,
    expanded: Boolean,
    working: Boolean,
    onToggle: () -> Unit,
) {
    val disclosure = stringResource(if (expanded) R.string.chat_process_expanded else R.string.chat_process_collapsed)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .testTag("chat-process-toggle-${process.id}")
                .semantics { stateDescription = disclosure }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.chat_process_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.chat_process_summary, process.summary.operations, process.summary.thoughts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (process.summary.errors > 0) Text(
                    stringResource(R.string.chat_process_errors, process.summary.errors),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
        }
    }
}
