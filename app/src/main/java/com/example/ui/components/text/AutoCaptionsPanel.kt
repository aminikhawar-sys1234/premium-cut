package com.example.ui.components.text

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.TextClip
import com.example.engine.text.registry.RegisteredTextTemplate
import com.example.ui.StudioViewModel
import kotlinx.coroutines.launch

/**
 * Auto Captions Panel covering ~70% of the screen height.
 * Simple lightweight blue + green visual treatment with white text.
 */
@Composable
fun AutoCaptionsPanel(
  viewModel: StudioViewModel,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val timeline by viewModel.timelineEngine.timeline.collectAsState()

  val spokenLanguages = listOf("Urdu", "English", "Hindi", "Chinese", "Arabic")
  var selectedSpokenLanguage by remember { mutableStateOf("English") }
  var isSpokenLangExpanded by remember { mutableStateOf(false) }

  val outputLanguages = listOf("Urdu", "English", "Hindi", "Arabic", "Chinese")
  var selectedOutputLanguage by remember { mutableStateOf("English") }

  val installedTemplates = rememberInstalledTextTemplates()
  var selectedTemplateId by remember { mutableStateOf<String?>(null) }

  var isGenerating by remember { mutableStateOf(false) }
  var detectSpeakers by remember { mutableStateOf(false) }
  val colorCodeSpeakers by viewModel.colorCodeSpeakers.collectAsState()
  var prefixSpeakerNames by remember { mutableStateOf(false) }
  val speakers by viewModel.captionSpeakers.collectAsState()
  val clipSpeakerMap by viewModel.clipSpeakerMap.collectAsState()
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // Simple lightweight blue + green background
  val panelBackground = Brush.verticalGradient(
    colors = listOf(
      Color(0xFF0D2538), // Deep blue
      Color(0xFF092925)  // Subtle deep emerald green
    )
  )

  Column(
    modifier = modifier
      .fillMaxWidth()
      .wrapContentHeight()
      .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .background(panelBackground)
      .border(1.dp, Color(0xFF1E404E), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .padding(horizontal = 14.dp, vertical = 6.dp)
      .testTag("auto_captions_panel")
  ) {
    // Top Section: Slim Header with Close Button + Spoken Language Selector
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // ❌ Close Button
      IconButton(
        onClick = onClose,
        modifier = Modifier
          .size(32.dp)
          .testTag("auto_captions_close_btn")
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Close",
          tint = Color.White,
          modifier = Modifier.size(20.dp)
        )
      }

      // Spoken Language Selector
      Box {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xFF133644),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)),
          modifier = Modifier
            .clickable { isSpokenLangExpanded = !isSpokenLangExpanded }
            .testTag("spoken_language_selector")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text(
              text = "Spoken: $selectedSpokenLanguage",
              color = Color.White,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )
            Icon(
              imageVector = Icons.Default.KeyboardArrowDown,
              contentDescription = "Select Spoken Language",
              tint = Color(0xFF10B981),
              modifier = Modifier.size(16.dp)
            )
          }
        }

        DropdownMenu(
          expanded = isSpokenLangExpanded,
          onDismissRequest = { isSpokenLangExpanded = false },
          modifier = Modifier.background(Color(0xFF0F2C3A))
        ) {
          spokenLanguages.forEach { lang ->
            DropdownMenuItem(
              text = {
                Text(
                  text = lang,
                  color = if (selectedSpokenLanguage == lang) Color(0xFF10B981) else Color.White,
                  fontWeight = if (selectedSpokenLanguage == lang) FontWeight.Bold else FontWeight.Normal
                )
              },
              onClick = {
                selectedSpokenLanguage = lang
                isSpokenLangExpanded = false
              }
            )
          }
        }
      }
    }

    // Scrollable Content
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f, fill = false)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // --- Section: Text Templates ---
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Text Templates",
          color = Color.White,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold
        )

        // Horizontally scrollable template selection area
        if (installedTemplates.isEmpty()) {
          // Clean empty state — NO fake/dummy templates!
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(72.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF102834))
              .border(1.dp, Color(0xFF1C3B49), RoundedCornerShape(10.dp))
              .padding(12.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "No caption templates installed. Installed templates will appear here.",
              color = Color(0xFF94A3B8),
              fontSize = 12.sp,
              textAlign = TextAlign.Center
            )
          }
        } else {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            installedTemplates.forEach { template ->
              val isSelected = selectedTemplateId == template.id
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFF102834),
                border = androidx.compose.foundation.BorderStroke(
                  1.dp,
                  if (isSelected) Color(0xFF10B981) else Color(0xFF1C3B49)
                ),
                modifier = Modifier
                  .width(100.dp)
                  .height(60.dp)
                  .clickable {
                    selectedTemplateId = if (isSelected) null else template.id
                  }
              ) {
                Box(
                  contentAlignment = Alignment.Center,
                  modifier = Modifier.padding(6.dp)
                ) {
                  Text(
                    text = template.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                  )
                }
              }
            }
          }
        }
      }

      // --- Section: Output Text Language ---
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Output Text Language",
          color = Color.White,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold
        )

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          outputLanguages.forEach { lang ->
            val isSelected = selectedOutputLanguage == lang
            FilterChip(
              selected = isSelected,
              onClick = { selectedOutputLanguage = lang },
              label = {
                Text(
                  text = lang,
                  color = if (isSelected) Color(0xFF092925) else Color.White,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  fontSize = 12.sp
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF10B981),
                containerColor = Color(0xFF133644)
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = Color(0xFF1E4557),
                selectedBorderColor = Color(0xFF10B981),
                enabled = true,
                selected = isSelected
              )
            )
          }
        }
      }

      // Status or Error Messaging (clean production error feedback, zero fake mock data)
      if (statusMessage != null) {
        Text(
          text = statusMessage ?: "",
          color = Color(0xFF38BDF8),
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 4.dp)
        )
      }

      if (errorMessage != null) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color(0x33EF4444),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = errorMessage ?: "",
            color = Color(0xFFFCA5A5),
            fontSize = 12.sp,
            modifier = Modifier.padding(10.dp)
          )
        }
      }
    }

    // --- Speaker detection (MFCC diarization) ---
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Detect speakers", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Label & colour captions per speaker", color = Color(0xFF9CA3AF), fontSize = 10.sp)
      }
      Switch(checked = detectSpeakers, onCheckedChange = { detectSpeakers = it }, modifier = Modifier.testTag("detect_speakers_switch"))
    }
    if (detectSpeakers) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(selected = colorCodeSpeakers, onClick = { viewModel.setColorCodeSpeakers(!colorCodeSpeakers) }, label = { Text("Colour-code", fontSize = 11.sp) })
        FilterChip(selected = prefixSpeakerNames, onClick = { prefixSpeakerNames = !prefixSpeakerNames }, label = { Text("Name prefix", fontSize = 11.sp) })
      }
    }
    if (speakers.isNotEmpty()) {
      SpeakerCardsRow(
        speakers = speakers,
        clipCounts = speakers.associate { sp -> sp.id to clipSpeakerMap.count { it.value == sp.id } },
        onColor = { id, argb -> viewModel.setSpeakerColor(id, argb) },
        onRename = { id, name -> viewModel.renameSpeaker(id, name) },
      )
    }

    Spacer(modifier = Modifier.height(10.dp))

    // --- Generate Button ---
    Button(
      onClick = {
        if (isGenerating) return@Button
        isGenerating = true
        errorMessage = null
        statusMessage = "Analyzing audio tracks for $selectedSpokenLanguage speech..."

        coroutineScope.launch {
          try {
            // Real pipeline execution via ViewModel / AIToolsService
            val hasMedia = timeline.videoClips.isNotEmpty() || timeline.audioClips.isNotEmpty()
            if (!hasMedia) {
              isGenerating = false
              statusMessage = null
              errorMessage = "No media on timeline: Please import a video or audio clip before generating captions."
              return@launch
            }

            // Execute real caption transcription
            var speakerResult: com.example.ui.StudioViewModel.SpeakerCaptionResult? = null
            val result = if (detectSpeakers) {
              statusMessage = "Transcribing speech in Firebase and detecting speakers..."
              viewModel.generateSpeakerCaptionClips(selectedSpokenLanguage).also { r ->
                speakerResult = r.getOrNull()
              }.map { it.clips }
            } else {
              statusMessage = "Transcribing speech with the Firebase speech engine..."
              viewModel.aiTools.generateAutoCaptions(timeline, selectedSpokenLanguage)
            }
            if (result.isSuccess) {
              val rawClips = result.getOrNull() ?: emptyList()
              if (rawClips.isEmpty()) {
                isGenerating = false
                statusMessage = null
                errorMessage = "No speech detected in audio stream."
                return@launch
              }

              // Apply translation if output language differs from spoken language
              val finalClips = if (!selectedSpokenLanguage.equals(selectedOutputLanguage, ignoreCase = true)) {
                statusMessage = "Translating captions into $selectedOutputLanguage..."
                val translationRes = viewModel.aiTools.translateCaptions(rawClips, selectedOutputLanguage)
                if (translationRes.isSuccess) translationRes.getOrNull() ?: rawClips else rawClips
              } else {
                rawClips
              }

              // Apply selected template style if any was chosen
              val styledClips = if (selectedTemplateId != null) {
                val chosenTemplate = installedTemplates.find { it.id == selectedTemplateId }
                if (chosenTemplate != null) {
                  finalClips.map { clip ->
                    clip.copy(
                      fontFamily = chosenTemplate.templateClip.fontFamily,
                      fontSizeSp = chosenTemplate.templateClip.fontSizeSp,
                      textColor = chosenTemplate.templateClip.textColor,
                      strokeWidth = chosenTemplate.templateClip.strokeWidth,
                      strokeColor = chosenTemplate.templateClip.strokeColor,
                      animationIn = chosenTemplate.templateClip.animationIn,
                      hasBackground = chosenTemplate.templateClip.hasBackground,
                      backgroundColor = chosenTemplate.templateClip.backgroundColor
                    )
                  }
                } else finalClips
              } else finalClips

              // Apply speaker colours / name prefixes (positional match with the diarized run).
              val sr = speakerResult
              val speakerStyled = if (sr != null && sr.clips.size == styledClips.size && sr.speakers.isNotEmpty()) {
                val byId = sr.speakers.associateBy { it.id }
                val clipToSpeaker = HashMap<String, String>()
                val out = styledClips.mapIndexed { i, clip ->
                  val sp = sr.speakerIds[i]?.let { byId[it] }
                  if (sp == null) clip else {
                    clipToSpeaker[clip.id] = sp.id
                    clip.copy(
                      speakerId = sp.id,
                      textColor = if (colorCodeSpeakers) sp.colorArgb else clip.textColor,
                      text = if (prefixSpeakerNames) "${sp.label}: ${clip.text}" else clip.text,
                    )
                  }
                }
                viewModel.registerSpeakerClips(clipToSpeaker, sr.speakers)
                out
              } else styledClips

              // Commit real generated caption TextClip objects to timeline
              speakerStyled.forEach { clip ->
                viewModel.timelineEngine.addTextClipObject(clip)
              }

              isGenerating = false
              if (detectSpeakers && speakerResult?.speakers?.size == 1) {
                statusMessage = "Captions generated. Only one speaker was detected."
                onClose()
              } else if (detectSpeakers && (speakerResult?.speakers?.size ?: 0) > 1) {
                // Keep the panel open so the Speaker 1 / Speaker 2 cards are visible right away
                // (rename / recolour). The user closes it with the X button.
                statusMessage = "Captions generated. ${speakerResult?.speakers?.size} speakers detected — tap a name to rename or pick a colour."
              } else {
                statusMessage = "Captions generated successfully!"
                onClose()
              }
            } else {
              isGenerating = false
              statusMessage = null
              val ex = result.exceptionOrNull()
              errorMessage = ex?.message ?: "Speech recognition service unavailable. Please check your network or AI settings."
            }
          } catch (e: Exception) {
            isGenerating = false
            statusMessage = null
            errorMessage = e.message ?: "An unexpected error occurred during caption generation."
          }
        }
      },
      enabled = !isGenerating,
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF10B981),
        disabledContainerColor = Color(0xFF10B981).copy(alpha = 0.5f)
      ),
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("generate_captions_btn")
    ) {
      if (isGenerating) {
        CircularProgressIndicator(
          color = Color.White,
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Generating...",
          color = Color.White,
          fontWeight = FontWeight.Bold
        )
      } else {
        Icon(
          imageVector = Icons.Default.Subtitles,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Generate Captions",
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }
  }
}


@Composable
private fun SpeakerCardsRow(
  speakers: List<com.ahstudio.captions.core.model.CaptionSpeaker>,
  clipCounts: Map<String, Int>,
  onColor: (String, Long) -> Unit,
  onRename: (String, String) -> Unit,
) {
  val palette = remember { listOf(0xFF4FC3F7, 0xFFFFB74D, 0xFF81C784, 0xFFF06292, 0xFFBA68C8, 0xFFFFF176) }
  var editing by remember { mutableStateOf<String?>(null) }
  var draft by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    speakers.forEach { sp ->
      Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF151C2C), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(sp.colorArgb)))
            Spacer(Modifier.width(8.dp))
            if (editing == sp.id) {
              OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = true,
                modifier = Modifier.weight(1f).height(48.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
              )
              TextButton(onClick = { onRename(sp.id, draft); editing = null }) { Text("Save", fontSize = 11.sp) }
            } else {
              Text(
                sp.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable { draft = sp.label; editing = sp.id },
              )
              Text("${clipCounts[sp.id] ?: 0} captions", color = Color(0xFF9CA3AF), fontSize = 10.sp)
            }
          }
          Spacer(Modifier.height(6.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            palette.forEach { c ->
              Box(
                Modifier.size(22.dp).clip(CircleShape).background(Color(c))
                  .border(if (c == sp.colorArgb) 2.dp else 0.dp, Color.White, CircleShape)
                  .clickable { onColor(sp.id, c) }
              )
            }
          }
        }
      }
    }
  }
}
