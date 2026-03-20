# BirdMIDI 🐦🎶

**BirdMIDI** is a modern Android application that transcribes birdsong into editable MIDI data in real-time. It uses the robust **YIN Pitch Detection Algorithm** combined with a high-fidelity **48kHz audio capture** pipeline to track the high-frequency fundamentals of complex birdsong (up to 12kHz).

## Features
- **Real-Time Pitch Tracking**: Ultra-low latency analysis using a pure-Kotlin YIN algorithm.
- **High-Frequency Capture**: 48kHz/16kHz "Scaling Trick" specifically optimized for birdsong.
- **MIDI Export**: Save your transcriptions as standard `.mid` files for use in any DAW.
- **Adjustable Resolution**: Scale your transcriptions up to 8 bars (128 steps) for rhythmic precision.
- **Visual Diagnostics**: Real-time waveform visualization and technical diagnostics (Hz, Confidence, Rate).

## Technical Details
- **Engine**: YIN Algorithm (Model-free, mathematical pitch estimation).
- **Core**: Jetpack Compose UI with Kotlin Coroutines for asynchronous audio processing.
- **Audio Source**: `MediaRecorder.AudioSource.CAMCORDER` for directional hardware capture.
- **Sample Rate**: 48,000Hz internal processing.

## Getting Started
1. Clone the repository.
2. Open in Android Studio (Iguana or later).
3. Build and run on a physical Android device (Pixel recommended).

## License
MIT
