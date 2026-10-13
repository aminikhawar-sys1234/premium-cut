package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.MarkerType
import com.example.domain.model.VideoClip
import com.example.engine.ai.SmartMediaAnalysis
import com.example.engine.ai.TimelineBeats
import com.example.engine.history.TimelineActionType
import com.example.engine.timeline.AttributeGroup
import com.example.ui.StudioViewModel
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val Accent = Color(0xFFE040FB)

/**
 * One panel for the CapCut-style smart edits that work on the user's own footage: beat sync on their own
 * audio, scene splitting, auto reframe, filler word / pause removal, per-word caption editing and
 * paste attributes. Every action is a single undo step in the timeline engine.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SmartToolsPanel(
  viewModel: StudioViewModel,
  modifier: Modifier = Modifier,
  onClose: () -> Unit = {}
) {
  val engine = viewModel.timelineEngine
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val timeline by engine.timeline.collectAsState()
  val selected by engine.selectedElement.collectAsState()
  val selectedIds by engine.selectedClipIds.collectAsState()
  val playhead by engine.currentPositionMs.collectAsState()
  val copied by engine.copiedAttributes.collectAsState()

  var busy by remember { mutableStateOf<String?>(null) }
  var message by remember { mutableStateOf<String?>(null) }
  var beats by remember { mutableStateOf<TimelineBeats?>(null) }
  var everyNth by remember { mutableIntStateOf(2) }
  var sensitivity by remember { mutableFloatStateOf(0.5f) }
  var editingWord by remember { mutableStateOf<Int?>(null) }
  var wordDraft by remember { mutableStateOf("") }
  var groups by remember { mutableStateOf(AttributeGroup.values().toSet()) }

  fun targetVideo(): VideoClip? {
    val id = selected.clipIdOrNull ?: selectedIds.firstOrNull()
    return timeline.videoClips.firstOrNull { it.id == id }
      ?: timeline.overlayClips.firstOrNull { it.id == id }
      ?: timeline.videoClips.firstOrNull { playhead >= it.timelineStartMs && playhead < it.timelineStartMs + it.durationMs }
  }

  val captionId = (selected as? com.example.engine.SelectedTrackElement.Text)?.clipId
  val caption = timeline.textClips.firstOrNull { it.id == captionId }
  val spokenWordCount = timeline.textClips.sumOf { it.words.size }

  fun launchBusy(label: String, block: suspend () -> Unit) {
    if (busy != null) return
    busy = label
    message = null
    scope.launch {
      try {
        block()
      } catch (e: Exception) {
        message = "Failed: ${e.message ?: e.javaClass.simpleName}"
      } finally {
        busy = null
      }
    }
  }

  fun beatTimes(): List<Long> {
    val all = beats?.beatsMs ?: return emptyList()
    val picked = all.filterIndexed { i, _ -> i % everyNth == 0 }
    val out = ArrayList<Long>()
    for (t in picked) if (out.isEmpty() || t - out.last() >= 500L) out += t
    return out
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(StudioSurface)
      .padding(16.dp)
      .testTag("smart_tools_panel"),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Accent)
        Text("Smart Tools", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
      }
      IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary) }
    }

    busy?.let {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
        Text(it, fontSize = 12.sp, color = TextPrimary)
      }
    }
    message?.let { Text(it, fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("smart_tools_message")) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {

      SmartSection("Beat sync", "Finds the beat of your own music or video sound. No music library needed.") {
        val found = beats
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            enabled = busy == null,
            onClick = {
              launchBusy("Listening for the beat...") {
                val result = SmartMediaAnalysis.analyzeBeats(context, engine.timeline.value)
                beats = result
                message = if (result == null) "No steady beat found. Add a music clip or a video with a clear beat."
                else "${result.bpm.toInt()} BPM, ${result.beatsMs.size} beats in ${result.sourceName}" +
                  if (result.confidence < 0.2f) " (weak beat, check the markers)" else ""
              }
            },
            modifier = Modifier.testTag("smart_detect_beats")
          ) { Text("Detect beats") }
        }
        if (found != null) {
          Text("Cut every:", fontSize = 11.sp, color = TextSecondary)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 4, 8).forEach { n ->
              FilterChip(selected = everyNth == n, onClick = { everyNth = n }, label = { Text(if (n == 1) "beat" else "$n beats", fontSize = 11.sp) })
            }
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
              val n = engine.setMarkers(MarkerType.BEAT, beatTimes(), "Beat")
              message = "$n beat markers on the timeline."
            }, modifier = Modifier.testTag("smart_beat_markers")) { Text("Show markers") }
            Button(onClick = {
              val cuts = engine.splitMainTrackAtTimes(beatTimes(), TimelineActionType.BEAT_SYNC, "Cut on beats")
              message = if (cuts == 0) "Nothing to cut: the main track has no clip over those beats." else "Cut the main track on $cuts beats."
            }, modifier = Modifier.testTag("smart_beat_cut")) { Text("Cut on beats") }
            TextButton(onClick = { engine.clearMarkers(MarkerType.BEAT) }) { Text("Clear markers") }
          }
        }
      }

      SmartSection("Scene detect", "Splits a long clip wherever the shot changes.") {
        Text("Sensitivity", fontSize = 11.sp, color = TextSecondary)
        Slider(value = sensitivity, onValueChange = { sensitivity = it })
        Button(
          enabled = busy == null,
          onClick = {
            val clip = targetVideo()
            if (clip == null || !clip.isVideo) {
              message = "Select a video clip on the main track first."
            } else launchBusy("Scanning shots...") {
              val cuts = SmartMediaAnalysis.detectSceneChanges(context, clip, sensitivity)
              if (cuts.isEmpty()) message = "No scene changes found in this clip."
              else {
                val n = engine.splitMainTrackAtTimes(cuts, TimelineActionType.SCENE_SPLIT, "Split scenes")
                engine.setMarkers(MarkerType.SCENE, cuts, "Scene")
                message = "Split into ${n + 1} shots."
              }
            }
          },
          modifier = Modifier.testTag("smart_scene_split")
        ) { Text("Split at scene changes") }
      }

      SmartSection("Auto reframe", "Crops a landscape clip to the project frame and follows the person.") {
        Button(
          enabled = busy == null,
          onClick = {
            val clip = targetVideo()
            if (clip == null || !clip.isVideo) {
              message = "Select a video clip first."
            } else launchBusy("Following the subject...") {
              val plan = SmartMediaAnalysis.planAutoReframe(context, clip, timeline.aspectRatio.ratio)
              if (plan == null || plan.keys.isEmpty()) {
                message = "This clip already fits the project frame (or has a manual rotation)."
              } else {
                engine.applyAutoReframe(clip.id, plan.keys)
                message = if (plan.subjectFoundRatio < 0.3f) "No clear subject found, so the frame stays centered." else "Reframed, subject found in ${(plan.subjectFoundRatio * 100).toInt()}% of frames."
              }
            }
          },
          modifier = Modifier.testTag("smart_auto_reframe")
        ) { Text("Reframe selected clip") }
      }

      SmartSection("Clean up speech", "Uses the word timings of your captions. Generate captions first.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(enabled = busy == null, onClick = {
            val n = engine.removeFillerWords()
            message = if (n == 0) noWordsMessage(spokenWordCount, "No filler words found.") else "Removed $n filler words."
          }, modifier = Modifier.testTag("smart_remove_fillers")) { Text("Remove filler words") }
          Button(enabled = busy == null, onClick = {
            val n = engine.removePauses()
            message = if (n == 0) noWordsMessage(spokenWordCount, "No long pauses found.") else "Shortened $n pauses."
          }, modifier = Modifier.testTag("smart_remove_pauses")) { Text("Remove pauses") }
        }
      }

      SmartSection("Edit caption words", "Select a caption on the timeline, then tap a word.") {
        if (caption == null || caption.words.isEmpty()) {
          Text(if (caption == null) "No caption selected." else "This caption has no word timings.", fontSize = 11.sp, color = TextSecondary)
        } else {
          val tokens = com.example.engine.captions.CaptionWordOps.tokens(caption)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tokens.forEachIndexed { i, word ->
              AssistChip(onClick = { editingWord = i; wordDraft = word }, label = { Text(word, fontSize = 12.sp) })
            }
          }
        }
      }

      SmartSection("Paste attributes", "Copy the look of one clip and stamp it on others. Speed and trim are never copied.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          AttributeGroup.values().forEach { g ->
            FilterChip(
              selected = g in groups,
              onClick = { groups = if (g in groups) groups - g else groups + g },
              label = { Text(g.label, fontSize = 11.sp) }
            )
          }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = {
            val clip = targetVideo()
            message = if (clip != null && engine.copyClipAttributes(clip.id)) "Copied attributes of ${clip.name}." else "Select a video clip to copy from."
          }, modifier = Modifier.testTag("smart_copy_attrs")) { Text("Copy from selected") }
          Button(enabled = copied != null && groups.isNotEmpty(), onClick = {
            val ids = (selectedIds + listOfNotNull(selected.clipIdOrNull)).toSet()
            val n = engine.pasteClipAttributes(ids, groups)
            message = if (n == 0) "Select the clips to paste onto (not the source clip)." else "Pasted onto $n clips."
          }, modifier = Modifier.testTag("smart_paste_attrs")) { Text("Paste to selected") }
          Button(enabled = copied != null && groups.isNotEmpty(), onClick = {
            val n = engine.pasteClipAttributes(timeline.videoClips.map { it.id }.toSet(), groups)
            message = if (n == 0) "All clips already look the same." else "Pasted onto $n clips."
          }, modifier = Modifier.testTag("smart_paste_all")) { Text("Paste to all") }
        }
      }
    }
  }

  val index = editingWord
  if (index != null && caption != null) {
    AlertDialog(
      onDismissRequest = { editingWord = null },
      title = { Text("Edit word") },
      text = {
        OutlinedTextField(value = wordDraft, onValueChange = { wordDraft = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
      },
      confirmButton = {
        TextButton(onClick = {
          engine.editCaptionWord(caption.id, index, wordDraft)
          editingWord = null
        }) { Text("Save") }
      },
      dismissButton = {
        Row {
          TextButton(onClick = {
            engine.editCaptionWord(caption.id, index, null)
            editingWord = null
          }) { Text("Delete word", color = Color(0xFFFF5252)) }
          TextButton(onClick = { editingWord = null }) { Text("Cancel") }
        }
      }
    )
  }
}

private fun noWordsMessage(spokenWordCount: Int, whenWordsExist: String): String =
  if (spokenWordCount == 0) "No captions with word timings yet. Generate captions in the AI Suite first." else whenWordsExist

@Composable
private fun SmartSection(title: String, hint: String, content: @Composable () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(StudioSurfaceVariant, RoundedCornerShape(12.dp))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    Text(hint, fontSize = 11.sp, color = TextSecondary)
    content()
  }
}
