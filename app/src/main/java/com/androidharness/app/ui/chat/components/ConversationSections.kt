package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind

internal val ConversationKind.titleRes get() = when (this) {
    ConversationKind.CODE -> R.string.conversation_code
    ConversationKind.CHAT -> R.string.conversation_chat
    ConversationKind.PHONE -> R.string.conversation_phone
}
internal val ConversationKind.sectionIcon get() = when (this) {
    ConversationKind.CODE -> Icons.Outlined.Code
    ConversationKind.CHAT -> Icons.Outlined.ChatBubbleOutline
    ConversationKind.PHONE -> Icons.Outlined.PhoneAndroid
}

@Composable
internal fun ConversationSectionSwitcher(
    config: ConversationConfig,
    enabled: Boolean,
    onSelect: (ConversationKind) -> Unit,
) {
    val kinds = ConversationKind.entries
    // Move the pill on tap; navigation catches up a frame or two later. If the switch is
    // refused (a run is saving, say), the pill slides back to the real section.
    var shown by remember(config.kind) { mutableStateOf(config.kind) }
    val actual by rememberUpdatedState(config.kind)
    LaunchedEffect(shown) {
        if (shown != actual) { kotlinx.coroutines.delay(1500); shown = actual }
    }
    com.androidharness.app.ui.SlidingSegmentedControl(
        options = kinds.map { kind ->
            com.androidharness.app.ui.SegmentOption(
                stringResource(kind.titleRes), kind.sectionIcon,
            )
        },
        selectedIndex = kinds.indexOf(shown).coerceAtLeast(0),
        onSelect = { shown = kinds[it]; onSelect(kinds[it]) },
        enabled = enabled,
        height = 52.dp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
internal fun ConversationModeLabel(config: ConversationConfig, workspaceName: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(config.kind.sectionIcon, null,
            Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val section = stringResource(config.kind.titleRes)
        val detail = workspaceName?.takeIf { !config.isChat && it.isNotBlank() }
        Text(section + detail?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
