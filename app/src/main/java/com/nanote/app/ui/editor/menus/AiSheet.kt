package com.nanote.app.ui.editor.menus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanote.app.R
import com.nanote.app.core.ai.AiService
import com.nanote.app.core.data.SettingsStore
import com.nanote.app.core.model.AppLanguage
import com.nanote.app.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * AI bottom sheet. Two modes:
 *  - translate: auto-runs [AiService.translate] on the selected text
 *  - ask: chat-style Q&A with the selected text as context
 * Shows a friendly configuration hint when no AI provider is set up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSheet(
    initialText: String,
    translateMode: Boolean,
    aiService: AiService,
    settings: SettingsStore,
    onDismiss: () -> Unit
) {
    val aiConfig by settings.aiConfig.collectAsStateWithLifecycle()
    val appLanguage by settings.language.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val targetLanguage = when (appLanguage) {
        AppLanguage.ARABIC -> "Arabic"
        AppLanguage.ENGLISH -> "English"
        AppLanguage.SYSTEM ->
            if (configuration.locale?.language?.startsWith("ar") == true) "Arabic" else "English"
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            // ---- header ----
            Row(
                Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.ai_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(14.dp))

            when {
                !aiConfig.isConfigured -> AiNotConfigured(onDismiss = onDismiss)
                translateMode -> AiTranslate(
                    initialText = initialText,
                    aiService = aiService,
                    targetLanguage = targetLanguage
                )
                else -> AiAsk(initialText = initialText, aiService = aiService)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Not configured
// ---------------------------------------------------------------------------

@Composable
private fun AiNotConfigured(onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Surface(
            shape = RoundedCornerShape(Dimens.radiusMd),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.ai_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.ai_configure))
        }
    }
}

// ---------------------------------------------------------------------------
// Translate mode
// ---------------------------------------------------------------------------

@Composable
private fun AiTranslate(
    initialText: String,
    aiService: AiService,
    targetLanguage: String
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun startTranslation() {
        loading = true
        error = null
        scope.launch {
            aiService.translate(initialText, targetLanguage)
                .onSuccess { translation ->
                    result = translation
                    loading = false
                }
                .onFailure {
                    error = it.message ?: ""
                    loading = false
                }
        }
    }

    // auto-run on open
    LaunchedEffect(Unit) { startTranslation() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        // source text
        Surface(
            shape = RoundedCornerShape(Dimens.radiusMd),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                initialText,
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    stringResource(R.string.ai_thinking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error != null -> Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.ai_error),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    IconButton(onClick = { startTranslation() }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.retry)
                        )
                    }
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> SelectionContainer {
                Text(
                    result.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Ask mode
// ---------------------------------------------------------------------------

@Composable
private fun AiAsk(
    initialText: String,
    aiService: AiService
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Pair<Boolean, String>>() } // (isUser, text)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastQuery by remember { mutableStateOf<String?>(null) }
    val listState = rememberScrollState()

    fun ask(question: String) {
        loading = true
        error = null
        scope.launch {
            aiService.ask(
                systemPrompt = "You are a helpful assistant inside a note-taking app. Answer concisely.",
                userPrompt = "Context:\n$initialText\n\nQuestion: $question"
            )
                .onSuccess { answer ->
                    messages.add(false to answer)
                    loading = false
                }
                .onFailure {
                    error = it.message ?: ""
                    loading = false
                }
        }
    }

    fun send() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        messages.add(true to q)
        lastQuery = q
        query = ""
        ask(q)
    }

    // keep the newest message visible
    LaunchedEffect(messages.size, loading) {
        listState.animateScrollTo(listState.maxValue)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        // selected text as context chip
        if (initialText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(Dimens.radiusMd),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    initialText,
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (messages.isNotEmpty() || loading || error != null) {
            Column(
                Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(listState)
            ) {
                messages.forEach { (isUser, text) ->
                    ChatBubble(
                        isUser = isUser,
                        text = text,
                        onCopy = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Nanote", text))
                            Toast.makeText(
                                context,
                                context.getString(R.string.copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }

                if (loading) {
                    Row(
                        Modifier.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            stringResource(R.string.ai_thinking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (error != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.ai_error),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        IconButton(onClick = { lastQuery?.let { ask(it) } }) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.retry)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- input row ----
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.ai_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            FilledIconButton(
                onClick = { send() },
                enabled = query.isNotBlank() && !loading
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.ai_send)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Chat bubble
// ---------------------------------------------------------------------------

@Composable
private fun ChatBubble(
    isUser: Boolean,
    text: String,
    onCopy: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 280.dp)
            ) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (!isUser) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.action_copy),
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
