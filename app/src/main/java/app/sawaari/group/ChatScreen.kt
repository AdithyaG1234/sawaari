package app.sawaari.group

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One-tap messages that are quick to send at a stop, without typing. */
private val QUICK = listOf("On my way", "Fuel stop", "Wait for me", "All good", "Slow down", "Reached", "Puncture, need help")

/** The group's chat: text, one-tap quick messages and hold-to-talk voice notes. */
@Composable
fun ChatScreen(repo: GroupRepository, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by repo.chat.collectAsState()
    val group by repo.group.collectAsState()
    val voice = remember { VoiceNotes(context) }
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hint = if (ok) "Hold the mic button to talk, let go to send." else "Sawaari needs the microphone to record voice notes."
    }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    BackHandler(onBack = onBack)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
        ChatSeen.mark(context, messages.lastOrNull()?.ts ?: 0L)
    }

    Column(modifier.imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text(group?.name ?: "Group chat", style = MaterialTheme.typography.titleMedium)
                Text("${group?.riders?.size ?: 0} riders", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) item {
                Text(
                    "No messages yet. Tap a quick message below, type, or hold the mic to send a voice note.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(messages, key = { it.id }) { m ->
                val mine = m.uid == repo.myUid
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (!mine) Text(m.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            if (m.voiceMs > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        if (playing == m.id) { voice.stopPlayback(); playing = null }
                                        else {
                                            playing = m.id
                                            scope.launch {
                                                val audio = repo.loadVoice(m.id)
                                                if (audio == null) { playing = null; hint = "Couldn't load that voice note. Check your internet." }
                                                else voice.play(audio) { playing = null }
                                            }
                                        }
                                    }) { Icon(if (playing == m.id) Icons.Default.Stop else Icons.Default.PlayArrow, if (playing == m.id) "Stop" else "Play") }
                                    Text("Voice note · ${(m.voiceMs + 500) / 1000} s")
                                }
                            } else {
                                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(time(m.ts), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }
            }
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QUICK) { q -> AssistChip(onClick = { repo.sendText(q) }, label = { Text(q) }) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(500) },
                placeholder = { Text(if (recording) "Recording… let go to send" else "Message") },
                modifier = Modifier.weight(1f), maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            if (text.isNotBlank()) {
                IconButton(onClick = { repo.sendText(text); text = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            } else {
                // Hold to talk, let go to send.
                Box(
                    Modifier.size(52.dp)
                        .background(if (recording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary, CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                if (!voice.start()) { hint = "Couldn't open the microphone."; return@detectTapGestures }
                                recording = true
                                hint = null
                                val released = tryAwaitRelease()
                                recording = false
                                val note = voice.stop()
                                when {
                                    !released -> Unit
                                    note == null -> hint = "Hold the mic button a little longer to record."
                                    else -> repo.sendVoice(note.first, note.second)
                                }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Mic, "Hold to record a voice note", tint = Color.White) }
            }
        }
    }
}

private fun time(ts: Long) = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(ts))

/** Remembers the newest message this rider has seen, for the unread count. */
object ChatSeen {
    fun last(context: android.content.Context) = context.getSharedPreferences("group", android.content.Context.MODE_PRIVATE).getLong("chat_seen", 0L)
    fun mark(context: android.content.Context, ts: Long) {
        if (ts > last(context)) context.getSharedPreferences("group", android.content.Context.MODE_PRIVATE).edit().putLong("chat_seen", ts).apply()
    }
}
