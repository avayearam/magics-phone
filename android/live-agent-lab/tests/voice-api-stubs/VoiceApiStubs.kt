package io.github.sparkcyf.voicesipagent.voice

data class PcmFormat(val sampleRate: Int) {
    init { require(sampleRate == 8000 || sampleRate == 16000) }
    val frameSamples: Int get() = sampleRate / 50
    fun silence() = ShortArray(frameSamples)
    companion object {
        val NARROWBAND = PcmFormat(8000)
        val WIDEBAND = PcmFormat(16000)
    }
}
fun interface FrameClockListener { fun onFrame() }
interface MediaPort {
    val format: PcmFormat
    val isClockSource: Boolean
    fun setClockListener(listener: FrameClockListener?)
    fun readFrame(): ShortArray
    fun writeFrame(frame: ShortArray)
}
