package com.example.ui.components.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.StudioViewModel

@Composable
fun SoundsLibraryPanel(
  viewModel: StudioViewModel,
  onClose: () -> Unit,
  onCompleteAndClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  DeviceAudioBrowserPanel(
    viewModel = viewModel,
    title = "Device sounds",
    emptyMessage = "No audio found on this device. Import a real sound file to add it to the timeline.",
    onClose = onClose,
    onCompleteAndClose = onCompleteAndClose,
    modifier = modifier
  )
}
