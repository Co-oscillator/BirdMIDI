package com.birdmidi.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class DetectedNote(
    val midiNote: Int,
    val startTime: Long, 
    var endTime: Long = -1
)

class AudioAnalyzer(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    
    private val _currentPitch = MutableStateFlow(-1f)
    val currentPitch = _currentPitch.asStateFlow()
    
    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence = _confidence.asStateFlow()

    private val _sampleRate = MutableStateFlow(0)
    val sampleRate = _sampleRate.asStateFlow()
    
    private val _errorState = MutableStateFlow("")
    val errorState = _errorState.asStateFlow()
    
    private val _uiPitch = MutableStateFlow(-1f)
    val uiPitch = _uiPitch.asStateFlow()

    private val _uiConfidence = MutableStateFlow(0f)
    val uiConfidence = _uiConfidence.asStateFlow()

    private val _modelInfo = MutableStateFlow("Algorithm: YIN (Scale 4x)")
    val modelInfo = _modelInfo.asStateFlow()
    
    private val _detectedNotes = MutableStateFlow<List<DetectedNote>>(emptyList())
    val detectedNotes = _detectedNotes.asStateFlow()

    private var lastMidiNote = -1
    private var recordingStartTime = 0L

    companion object {
        private const val ANALYSIS_SIZE = 1024 
        private const val TAG = "BirdMidi-Analyzer"
        private const val YIN_THRESHOLD = 0.15f
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        _detectedNotes.value = emptyList()
        _errorState.value = ""
        recordingStartTime = System.currentTimeMillis()
        lastMidiNote = -1
        isRecording = true
        
        val audioSource = MediaRecorder.AudioSource.CAMCORDER
        val sampleRate = 48000 
        
        val minBufferSize = max(AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 16384)
        
        audioRecord = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val builtInMic = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtInMic != null) {
                audioRecord?.setPreferredDevice(builtInMic)
                android.util.Log.d(TAG, "Preferred: Built-in Mic")
            }
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _errorState.value = "Init Failed"
            return
        }
        
        audioRecord?.startRecording()
        _sampleRate.value = audioRecord?.sampleRate ?: 0
        
        recordingJob = CoroutineScope(Dispatchers.Default).launch {
            val buffer = ShortArray(ANALYSIS_SIZE)
            val floatBuffer = FloatArray(ANALYSIS_SIZE)
            val yinBuffer = FloatArray(ANALYSIS_SIZE / 2)
            
            var lastX = 0f
            var lastY = 0f
            var lastUiTime = 0L

            try {
                while (isActive && isRecording) {
                    val read = audioRecord?.read(buffer, 0, ANALYSIS_SIZE) ?: 0
                    
                    if (read < 0) {
                        _errorState.value = "Read Err: $read"
                        break
                    }

                    if (read > 0) {
                        val actualSR = audioRecord?.sampleRate ?: 48000
                        val hpfCoeff = 0.99f 

                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val x = buffer[i].toFloat()
                            val y = x - lastX + hpfCoeff * lastY
                            lastX = x
                            lastY = y
                            floatBuffer[i] = y
                            sumSq += y * y
                        }
                        val rms = sqrt(sumSq / read).toFloat() / 32768f
                        _amplitude.value = rms

                        if (rms > 0.005f) {
                            val pitch = calculateYin(floatBuffer, read, yinBuffer)
                            if (pitch > 0) {
                                val scale = actualSR.toFloat() / 16000f
                                val actualHz = pitch * scale
                                if (actualHz > 250f && actualHz < 12000f) {
                                    _currentPitch.value = actualHz
                                    _confidence.value = 0.9f
                                    handlePitch((69 + 12 * log2(actualHz / 440.0)).roundToInt())
                                } else {
                                    _currentPitch.value = -1f
                                    _confidence.value = 0f
                                    handlePitch(-1)
                                }
                                
                                val now = System.currentTimeMillis()
                                if (now - lastUiTime > 100) {
                                    _uiPitch.value = if (actualHz > 250f) actualHz else -1f
                                    _uiConfidence.value = if (actualHz > 250f) 0.9f else 0f
                                    lastUiTime = now
                                }
                            } else {
                                _currentPitch.value = -1f
                                _confidence.value = 0f
                                val now = System.currentTimeMillis()
                                if (now - lastUiTime > 100) {
                                    _uiPitch.value = -1f
                                    _uiConfidence.value = 0f
                                    lastUiTime = now
                                }
                                handlePitch(-1)
                            }
                        } else {
                            _currentPitch.value = -1f
                            _confidence.value = 0f
                            handlePitch(-1)
                        }
                    }
                }
            } catch (e: Exception) {
                _errorState.value = "Job Error"
                android.util.Log.e(TAG, "Inference error: ${e.message}")
            }
        }
    }

    private fun calculateYin(buffer: FloatArray, size: Int, yinBuffer: FloatArray): Float {
        val halfSize = size / 2
        for (tau in 0 until halfSize) {
            var diff = 0f
            for (i in 0 until halfSize) {
                val delta = buffer[i] - buffer[i + tau]
                diff += delta * delta
            }
            yinBuffer[tau] = diff
        }

        var rSum = 0f
        yinBuffer[0] = 1f
        for (tau in 1 until halfSize) {
            rSum += yinBuffer[tau]
            yinBuffer[tau] = yinBuffer[tau] * tau / (if (rSum == 0f) 0.0001f else rSum)
        }

        var tauEst = -1
        for (tau in 1 until halfSize) {
            if (yinBuffer[tau] < YIN_THRESHOLD) {
                var curTau = tau
                while (curTau + 1 < halfSize && yinBuffer[curTau + 1] < yinBuffer[curTau]) {
                    curTau++
                }
                tauEst = curTau
                break
            }
        }

        if (tauEst == -1) return -1f
        return 16000f / tauEst
    }

    private fun handlePitch(midiNote: Int) {
        val currentTime = System.currentTimeMillis() - recordingStartTime
        if (midiNote != lastMidiNote) {
            val updatedList = _detectedNotes.value.toMutableList()
            if (lastMidiNote != -1) updatedList.lastOrNull()?.endTime = currentTime
            if (midiNote != -1) updatedList.add(DetectedNote(midiNote, currentTime))
            _detectedNotes.value = updatedList
            lastMidiNote = midiNote
        }
    }

    fun stopRecording(): Long {
        val duration = System.currentTimeMillis() - recordingStartTime
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        if (lastMidiNote != -1) {
            val updatedList = _detectedNotes.value.toMutableList()
            updatedList.lastOrNull()?.endTime = duration
            _detectedNotes.value = updatedList
        }
        lastMidiNote = -1
        return duration
    }
}
