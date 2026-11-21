package io.github.sparkcyf.voicesipagent.app

import java.util.Locale

/** Strict, transcript-only goodbye detection for automatic phone-call termination. */
internal object CallEndIntentDetector {
    private val nonWordCharacters = Regex("[^\\p{L}\\p{N}\\s']+")
    private val whitespace = Regex("\\s+")
    private val farewellAtEnd = Regex(
        """(?:^|\s)(?:خداحافظ|خدا\s+حافظ|خدانگهدار|خدا\s+نگهدار|خدا\s+نگه\s+دار|خدانگهدارت|خدا\s+نگهدارت|بدرود|بای|goodbye|bye(?:\s+bye)?|see\s+you(?:\s+later)?|talk\s+to\s+you\s+later)(?:\s+(?:عزیزم|عزیز|جان|پسر|دوست\s+من))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitFarewellAtEnd = Regex(
        """(?:^|\s)(?:خداحافظی\s+(?:کنیم|می\s+کنیم)|بیا\s+خداحافظی\s+کنیم|let\s+me\s+say\s+goodbye|we\s+should\s+say\s+goodbye)$""",
        RegexOption.IGNORE_CASE,
    )
    private val holdPhrase = Regex(
        """(?:^|\s)(?:صبر\s+کن|صبرکن|یه\s+لحظه|یک\s+لحظه|wait|hold\s+on|don't\s+hang\s+up|do\s+not\s+hang\s+up|not\s+yet)(?:\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    private fun normalize(value: String): String = value
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace('\u200c', ' ')
        .replace('\u200f', ' ')
        .lowercase(Locale.ROOT)
        .replace(nonWordCharacters, " ")
        .replace(whitespace, " ")
        .trim()

    private fun isFarewellAtEnd(value: String): Boolean =
        farewellAtEnd.containsMatchIn(value) || explicitFarewellAtEnd.containsMatchIn(value)

    fun isCallerGoodbye(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.isNotBlank() &&
            !holdPhrase.containsMatchIn(normalized) &&
            isFarewellAtEnd(normalized)
    }

    fun isAssistantFarewell(text: String): Boolean =
        isFarewellAtEnd(normalize(text))
}

/** Small state gate: a farewell is actionable only after the assistant turn completes. */
internal class GoodbyeHangupGate {
    private var goodbyePending = false
    private var assistantTurnComplete = false

    fun onCallerTranscript(text: String) {
        assistantTurnComplete = false
        goodbyePending = CallEndIntentDetector.isCallerGoodbye(text)
    }

    fun onAssistantTranscript(text: String) {
        if (CallEndIntentDetector.isAssistantFarewell(text)) goodbyePending = true
    }

    fun onAssistantTurnStarted() {
        assistantTurnComplete = false
    }

    fun onAssistantTurnCompleted(): Boolean {
        assistantTurnComplete = true
        return consumeReadyAfterTranscript()
    }

    fun onInterrupted() {
        goodbyePending = false
        assistantTurnComplete = false
    }

    fun consumeReadyAfterTranscript(): Boolean {
        if (!goodbyePending || !assistantTurnComplete) return false
        goodbyePending = false
        assistantTurnComplete = false
        return true
    }
}
