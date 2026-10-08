package com.example.ui.components.audio

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.audio.DeviceAudioLibrary
import com.example.engine.audio.DeviceAudioTrack
import com.example.engine.media.MediaMetadataHelper
import com.example.ui.StudioViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StudioDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun DeviceAudioBrowserPanel(
  viewModel: StudioViewModel,
  title: String,
  emptyMessage: String,
  onClose: () -> Unit,
  onCompleteAndClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var tracks by remember { mutableStateOf<List<DeviceAudioTrack>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var searchQuery by remember { mutableStateOf("") }
  var previewingUri by remember { mutableStateOf<String?>(null) }

  fun reload() {
    scope.launch {
      loading = true
      tracks = withContext(Dispatchers.IO) { DeviceAudioLibrary.query(context) }
      loading = false
    }
  }

  LaunchedEffect(Unit) { reload() }

  fun addTrack(titleText: String, uri: String, durationMs: Long) {
    scope.launch {
      viewModel.audioEngine.stopAudio()
      val waveform = withContext(Dispatchers.IO) {
        val path = Uri.parse(uri).path
        val file = if (path != null) File(path) else null
        if (file != null && file.exists()) viewModel.audioEngine.extractWaveformFromFile(file) else emptyList()
      }
      viewModel.timelineEngine.addAudioClip(
        title = titleText,
        durationMs = durationMs.coerceAtLeast(200L),
        uri = uri,
        waveformData = waveform
      )
      Toast.makeText(context, "Added \"$titleText\" to timeline", Toast.LENGTH_SHORT).show()
      onCompleteAndClose()
    }
  }

  val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      try {
        val dest = File(context.filesDir, "imported_audio_${System.currentTimeMillis()}.m4a")
        withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
          }
        }
        val metadata = MediaMetadataHelper.extractMetadata(context, dest.absolutePath)
        val duration = if (metadata.durationMs > 0L) metadata.durationMs else 0L
        if (duration <= 0L) {
          Toast.makeText(context, "Could not read audio duration", Toast.LENGTH_SHORT).show()
          return@launch
        }
        addTrack(
          titleText = dest.nameWithoutExtension,
          uri = dest.absolutePath,
          durationMs = duration
        )
      } catch (t: Throwable) {
        Toast.makeText(context, "Import failed: ${t.message}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  val filtered = remember(tracks, searchQuery) {
    if (searchQuery.isBlank()) tracks
    else tracks.filter {
      it.title.contains(searchQuery, ignoreCase = true) ||
        it.artist.contains(searchQuery, ignoreCase = true)
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(StudioDark)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF0F1523))
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search device audio…", color = Color.Gray, fontSize = 12.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = Color(0xFF161D2E),
          unfocusedContainerColor = Color(0xFF161D2E),
          focusedBorderColor = CyanAccent,
          unfocusedBorderColor = Color(0xFF26324A),
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
          .weight(1f)
          .height(44.dp)
      )
      IconButton(
        onClick = { importLauncher.launch("audio/*") },
        modifier = Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(Color(0xFF1E283E))
      ) {
        Icon(Icons.Default.UploadFile, contentDescription = "Import audio", tint = Color.White, modifier = Modifier.size(18.dp))
      }
      IconButton(
        onClick = {
          viewModel.audioEngine.stopAudio()
          onClose()
        },
        modifier = Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(Color(0xFF1E283E))
      ) {
        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
      }
    }

    Text(
      text = title,
      color = Color.White,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
    )

    when {
      loading -> {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(28.dp))
        }
      }
      filtered.isEmpty() -> {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color(0xFF7A889B), modifier = Modifier.size(36.dp))
          Spacer(Modifier.height(10.dp))
          Text(emptyMessage, color = Color(0xFF9EABB8), fontSize = 13.sp)
          Spacer(Modifier.height(12.dp))
          Button(
            onClick = { importLauncher.launch("audio/*") },
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
          ) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Import audio file", fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
        }
      }
      else -> {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          items(filtered, key = { it.id }) { track ->
            val isPreviewing = previewingUri == track.uri
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = Color(0xFF151C2C),
              border = BorderStroke(1.dp, if (isPreviewing) CyanAccent else Color(0xFF1E283E)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isPreviewing) CyanAccent else Color(0xFF1E283E))
                    .clickable {
                      if (isPreviewing) {
                        viewModel.audioEngine.stopAudio()
                        previewingUri = null
                      } else {
                        previewingUri = track.uri
                        viewModel.audioEngine.playUriPreview(track.uri)
                      }
                    },
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPreviewing) "Stop" else "Play",
                    tint = if (isPreviewing) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                  )
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Spacer(Modifier.height(2.dp))
                  val sec = track.durationMs / 1000f
                  Text(
                    text = "${track.artist} • ${String.format(Locale.US, "%.0fs", sec)}",
                    color = Color.Gray,
                    fontSize = 10.sp
                  )
                }
                Button(
                  onClick = { addTrack(track.title, track.uri, track.durationMs) },
                  shape = RoundedCornerShape(16.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                  modifier = Modifier.height(30.dp)
                ) {
                  Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                  Spacer(Modifier.width(2.dp))
                  Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }
      }
    }
  }
}
