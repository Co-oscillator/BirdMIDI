package com.birdmidi.app

import android.content.Context
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MidiExporter(private val context: Context) {
    
    fun writeMidiToStream(outputStream: OutputStream, notes: List<DetectedNote>, recordingDurationMs: Long, bars: Int) {
        if (notes.isEmpty()) {
            outputStream.close()
            return
        }

        // Use the actual recording duration provided by the analyzer/user
        val totalDurationMs = if (recordingDurationMs > 0) recordingDurationMs else (notes.last().endTime)
        
        val beatsPerBar = 4
        val totalBeats = bars * beatsPerBar
        val quarterNoteDurationMs = if (totalBeats > 0) totalDurationMs / totalBeats else totalDurationMs
        val ticksPerQuarterNote = 480
        val msToTicks = if (quarterNoteDurationMs > 0) ticksPerQuarterNote.toDouble() / quarterNoteDurationMs else 1.0

        val eventList = mutableListOf<MidiEvent>()
        for (note in notes) {
            val startTick = (note.startTime * msToTicks).toLong()
            val endTick = (if (note.endTime > 0) note.endTime else totalDurationMs) * msToTicks
            eventList.add(MidiEvent(startTick, 0x90.toByte(), note.midiNote.toByte(), 0x64.toByte()))
            eventList.add(MidiEvent(endTick.toLong(), 0x80.toByte(), note.midiNote.toByte(), 0x00.toByte()))
        }
        eventList.sortBy { it.tick }

        outputStream.write("MThd".toByteArray())
        outputStream.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1))
        outputStream.write(byteArrayOf((ticksPerQuarterNote shr 8).toByte(), (ticksPerQuarterNote and 0xFF).toByte()))

        val trackData = ByteArrayOutputStream()
        var currentTick = 0L
        for (event in eventList) {
            val delta = event.tick - currentTick
            if (delta < 0) continue
            writeVariableLengthValue(trackData, delta)
            trackData.write(byteArrayOf(event.status, event.data1, event.data2))
            currentTick = event.tick
        }
        trackData.write(byteArrayOf(0x00, 0xFF.toByte(), 0x2F, 0x00))

        val trackBytes = trackData.toByteArray()
        outputStream.write("MTrk".toByteArray())
        val trackSize = trackBytes.size
        outputStream.write(byteArrayOf(
            (trackSize shr 24 and 0xFF).toByte(),
            (trackSize shr 16 and 0xFF).toByte(),
            (trackSize shr 8 and 0xFF).toByte(),
            (trackSize and 0xFF).toByte()
        ))
        outputStream.write(trackBytes)
        outputStream.close()
    }

    fun export(notes: List<DetectedNote>, duration: Long, bars: Int): String {
        val fileName = "bird_song_${System.currentTimeMillis()}.mid"
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val file = File(musicDir, fileName)
        val fos = FileOutputStream(file)
        writeMidiToStream(fos, notes, duration, bars)
        return file.absolutePath
    }

    private fun writeVariableLengthValue(out: OutputStream, value: Long) {
        var v = value
        val buffer = mutableListOf<Byte>()
        buffer.add((v and 0x7F).toByte())
        while (v > 127) {
            v = v shr 7
            buffer.add(0, ((v and 0x7F) or 0x80).toByte())
        }
        for (b in buffer) out.write(b.toInt())
    }

    data class MidiEvent(val tick: Long, val status: Byte, val data1: Byte, val data2: Byte)
}
