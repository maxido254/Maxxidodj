package com.example.djapp

object NativeEngine {
    init { System.loadLibrary("djengine") }
    external fun start(): Boolean
    external fun stop()
    /** Loads the full mix (instant play). Resets position. */
    external fun loadDeck(deck: Int, pcmPath: String, sampleRate: Int)
    /** Swaps in 4 stems (vocals, drums, bass, other) without interrupting playback. */
    external fun setStems(deck: Int, paths: Array<String>)
    external fun setStemVolume(deck: Int, stem: Int, volume: Float)
    external fun setPlaying(deck: Int, playing: Boolean)
    external fun isPlaying(deck: Int): Boolean
    external fun setVolume(deck: Int, volume: Float)
    external fun setTempo(deck: Int, tempo: Float)
    external fun setCrossfader(x: Float)
    external fun getPosition(deck: Int): Double
    external fun getDuration(deck: Int): Double
    external fun seek(deck: Int, seconds: Double)
    external fun getLevel(): Float

    /** Scratch: while [active] the deck follows the platter, which is held at track position [targetSec]. */
    external fun setJog(deck: Int, active: Boolean, targetSec: Double)
    /** Loads a prepared track straight from its 4 stem files (vocals, drums, bass, other). */
    external fun loadDeckStems(deck: Int, paths: Array<String>, sampleRate: Int): Boolean
    /** Key lock: tempo changes without changing pitch. */
    external fun setKeylock(deck: Int, on: Boolean)
    /** 0 = fast, 1 = balanced (budget phones), 2 = safe (largest buffer). Restarts the audio stream. */
    external fun setAudioMode(mode: Int): Boolean
    /** Loop between two track positions (seconds). */
    external fun setLoop(deck: Int, startSec: Double, endSec: Double, on: Boolean)

    /** DJ filter: -1 = low-pass closed, 0 = off, +1 = high-pass closed. */
    external fun setFilter(deck: Int, value: Float)
    /** 3-band EQ: band 0 = bass, 1 = mid, 2 = treble; gain in dB (-26 = almost off, +12 = boost). */
    external fun setEq(deck: Int, band: Int, db: Float)
    external fun setReverb(deck: Int, wet: Float, room: Float)
    external fun setPhaser(deck: Int, amount: Float, rateHz: Float)

    external fun startRecording(path: String): Boolean
    external fun stopRecording()

    // ---- Sampler: 32 slots (4 banks x 8 pads) ----
    external fun loadSample(slot: Int, pcmPath: String, sampleRate: Int): Boolean
    external fun clearSample(slot: Int)
    external fun triggerSample(slot: Int, loop: Boolean)
    external fun stopSample(slot: Int)
    external fun isSamplePlaying(slot: Int): Boolean
    external fun setSampleVolume(v: Float)

    // ---- Master ----
    external fun setMasterGain(v: Float)
    /** 0 = smooth (constant power), 1 = sharp / cut, 2 = linear. */
    external fun setCrossfaderCurve(curve: Int)
}
