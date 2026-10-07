package com.ahstudio.audio.master.timeline

import com.ahstudio.audio.master.AudioEngineError
import com.ahstudio.audio.master.AudioEngineResult
import com.ahstudio.audio.master.clips.AudioClipOperations
import com.ahstudio.audio.master.clips.AudioClipValidator
import com.ahstudio.audio.master.commands.AudioEditCommand
import com.ahstudio.audio.master.commands.AudioUndoRedoAdapter
import com.ahstudio.audio.master.model.AudioClipModel
import com.ahstudio.audio.master.model.MasterAudioProject
import java.util.concurrent.CopyOnWriteArrayList

enum class OverlapStrategy { REJECT, TRIM_INCOMING, CROSSFADE, ALLOW }

object AudioOverlapResolver {
    fun resolve(project: MasterAudioProject, trackId: String?, strategy: OverlapStrategy): MasterAudioProject? {
        if (trackId == null || strategy == OverlapStrategy.ALLOW || strategy == OverlapStrategy.CROSSFADE) return project
        val track = project.trackById(trackId) ?: return project
        val sorted = track.clips.sortedBy { it.timelineStartSec }
        val out = mutableListOf<AudioClipModel>()
        for (clip in sorted) {
            var c = clip
            val prev = out.lastOrNull()
            if (prev != null && c.timelineStartSec < prev.timelineEndSec - 1e-9) {
                when (strategy) {
                    OverlapStrategy.REJECT -> return null
                    OverlapStrategy.TRIM_INCOMING -> {
                        c = try {
                            AudioClipOperations.trim(c, prev.timelineEndSec, c.timelineEndSec)
                        } catch (e: Exception) { return null }
                    }
                    else -> {}
                }
            }
            out.add(c)
        }
        return project.withTrack(track.copy(clips = out))
    }
}

object AudioTimelineValidator {
    fun validate(project: MasterAudioProject): Pair<List<String>, List<String>> {
        val fatal = mutableListOf<String>(); val warnings = mutableListOf<String>()
        val ids = HashSet<String>()
        for (t in project.tracks) {
            for (c in t.clips) {
                if (!ids.add(c.id)) fatal.add("duplicate clip id ${c.id}")
                val src = project.sourceById(c.sourceId)
                if (src == null) fatal.add("missing source ${c.sourceId}")
                else {
                    val issues = AudioClipValidator.validate(c, src.durationSec)
                    issues.forEach { if (it == "source coverage exceeds source length") fatal.add("${c.id}: $it") else warnings.add("${c.id}: $it") }
                }
            }
        }
        return fatal to warnings
    }
}

object AudioTimelineQuery {
    fun activeClipsAt(project: MasterAudioProject, t: Double): List<AudioClipModel> =
        project.tracks.asSequence().flatMap { it.clips.asSequence() }.filter { it.containsTime(t) }.toList()
}

class AudioTimelineController(private val undoRedo: AudioUndoRedoAdapter) {
    @Volatile var current: MasterAudioProject = MasterAudioProject(); private set
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(MasterAudioProject) -> Unit>()

    fun addListener(l: (MasterAudioProject) -> Unit) { listeners.add(l) }

    fun setProject(p: MasterAudioProject) {
        synchronized(lock) { current = p }
        listeners.forEach { it(p) }
    }

    fun submit(command: AudioEditCommand, strategy: OverlapStrategy = OverlapStrategy.CROSSFADE): AudioEngineResult<MasterAudioProject> {
        val next: MasterAudioProject; val previous: MasterAudioProject
        synchronized(lock) {
            previous = current
            val applied = try { command.apply(current) } catch (e: Exception) {
                return AudioEngineResult.Failure(AudioEngineError.INVALID_CLIP, e.message ?: "edit failed", e) }
            val resolved = AudioOverlapResolver.resolve(applied, command.affectedTrackId(), strategy)
                ?: return AudioEngineResult.Failure(AudioEngineError.OVERLAP_REJECTED, "overlap rejected on track ${command.affectedTrackId()}")
            val (fatal, _) = AudioTimelineValidator.validate(resolved)
            if (fatal.isNotEmpty()) return AudioEngineResult.Failure(AudioEngineError.INVALID_CLIP, fatal.joinToString("; "))
            current = resolved; next = resolved
            undoRedo.push(command, previous, next)
        }
        listeners.forEach { it(next) }
        return AudioEngineResult.Success(next)
    }

    fun undo(): Boolean { synchronized(lock) { val p = undoRedo.undo(current) ?: return false; current = p }; listeners.forEach { it(current) }; return true }
    fun redo(): Boolean { synchronized(lock) { val p = undoRedo.redo(current) ?: return false; current = p }; listeners.forEach { it(current) }; return true }
    fun canUndo() = undoRedo.canUndo(); fun canRedo() = undoRedo.canRedo()
    fun activeClipsAt(t: Double) = AudioTimelineQuery.activeClipsAt(current, t)
}
