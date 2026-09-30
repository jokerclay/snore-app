package com.minimal.snore.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.channels.FileChannel

/**
 * Google YAMNet Audio Classifier for filtering environmental noises (airplanes, traffic)
 * and confirming human snoring.
 */
class YamnetClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val classMap = mutableMapOf<Int, String>()

    companion object {
        const val SAMPLES_REQUIRED = 15600 // 0.975 seconds at 16kHz
        const val SNORE_INDEX = 38         // "Snoring"
        const val SNORT_INDEX = 41         // "Snort"

        // Environmental noise classes to explicitly filter out
        val NOISE_EXCLUSIONS = setOf(
            321, // "Traffic noise, roadway noise"
            329, // "Aircraft"
            330, // "Aircraft engine"
            331, // "Jet engine"
            334  // "Fixed-wing aircraft, airplane"
        )
    }

    init {
        loadClassMap()
        loadModel()
    }

    private fun loadClassMap() {
        try {
            context.assets.open("yamnet_class_map.csv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // Skip header: index,mid,display_name
                    reader.readLine()
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val parts = line.split(",", limit = 3)
                        if (parts.size >= 3) {
                            val index = parts[0].trim().toIntOrNull()
                            val name = parts[2].trim().replace("\"", "")
                            if (index != null) {
                                classMap[index] = name
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadModel() {
        try {
            val assetFileDescriptor = context.assets.openFd("yamnet.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class ClassificationResult(
        val isSnore: Boolean,
        val snoreScore: Float,
        val topCategory: String,
        val topScore: Float,
        val isAirplaneOrTraffic: Boolean
    )

    /**
     * Runs classification on 0.975s (15,600 samples) of 16-bit PCM audio.
     */
    fun classify(pcmSamples: ShortArray): ClassificationResult {
        val inter = interpreter ?: return ClassificationResult(
            isSnore = true,
            snoreScore = 1.0f,
            topCategory = "AcousticFallback",
            topScore = 1.0f,
            isAirplaneOrTraffic = false
        )

        // 1. Convert to normalized Float [-1.0f, 1.0f]
        val input = FloatArray(SAMPLES_REQUIRED)
        val copyCount = minOf(pcmSamples.size, SAMPLES_REQUIRED)
        for (i in 0 until copyCount) {
            input[i] = pcmSamples[i] / 32768.0f
        }

        // 2. Prepare output tensor [1, 521]
        val output = Array(1) { FloatArray(521) }
        val outputMap = HashMap<Int, Any>()
        outputMap[0] = output

        try {
            inter.runForMultipleInputsOutputs(arrayOf(input), outputMap)
            val scores = output[0]

            var maxScore = -1f
            var maxIndex = -1
            for (i in scores.indices) {
                if (scores[i] > maxScore) {
                    maxScore = scores[i]
                    maxIndex = i
                }
            }

            val snoreScore = maxOf(scores.getOrElse(SNORE_INDEX) { 0f }, scores.getOrElse(SNORT_INDEX) { 0f })

            // Check if airplane or vehicle noise dominated
            var isAirplaneOrTraffic = false
            for (idx in NOISE_EXCLUSIONS) {
                val score = scores.getOrElse(idx) { 0f }
                if (score > 0.30f && score > snoreScore) {
                    isAirplaneOrTraffic = true
                    break
                }
            }

            // Snore is confirmed if score is acceptable and not an airplane/traffic event
            val isConfirmedSnore = (snoreScore >= 0.20f) && !isAirplaneOrTraffic

            val topLabel = classMap[maxIndex] ?: "Class #$maxIndex"

            return ClassificationResult(
                isSnore = isConfirmedSnore,
                snoreScore = snoreScore,
                topCategory = topLabel,
                topScore = maxScore,
                isAirplaneOrTraffic = isAirplaneOrTraffic
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return ClassificationResult(
                isSnore = true,
                snoreScore = 1.0f,
                topCategory = "ErrorFallback",
                topScore = 1.0f,
                isAirplaneOrTraffic = false
            )
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
