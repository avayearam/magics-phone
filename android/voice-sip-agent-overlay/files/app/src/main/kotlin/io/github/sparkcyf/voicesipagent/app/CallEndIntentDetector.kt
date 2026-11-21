package io.github.sparkcyf.voicesipagent.app

import java.util.Locale

/** Transcript-only intent detection for automatic phone-call termination. */
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
    private val directEndCallCommandAtEnd = Regex(
        """(?:^|\s)(?:(?:لطفا|خواهشا)\s+)?(?:(?:تماس|مکالمه|گوشی)\s+(?:را|رو)\s+)?(?:قطع\s+کن|قطعش\s+کن|تموم\s+کن|تمام\s+کن|ببند|خاتمه\s+بده|پایان\s+بده)(?:\s+(?:لطفا|خواهشا))?$|(?:^|\s)(?:به\s+(?:تماس|مکالمه)\s+پایان\s+بده|دیگه\s+صحبت\s+نکنیم|hang\s+up(?:\s+(?:the\s+)?(?:phone|call))?|end\s+(?:the\s+)?call|end\s+this\s+call|disconnect(?:\s+(?:the\s+)?call)?|stop\s+the\s+call)$""",
        RegexOption.IGNORE_CASE,
    )
    private val holdPhrase = Regex(
        """(?:^|\s)(?:صبر\s+کن|صبرکن|یه\s+لحظه|یک\s+لحظه|قطع\s+نکن|قطعش\s+نکن|تماس\s+(?:را|رو)\s+قطع\s+نکن|مکالمه\s+(?:را|رو)\s+تموم\s+نکن|wait|hold\s+on|don't\s+hang\s+up|do\s+not\s+hang\s+up|don't\s+end\s+the\s+call|do\s+not\s+end\s+the\s+call|not\s+yet)(?:\s|$)""",
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

    fun isCallerEndCallCommand(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.isNotBlank() &&
            !holdPhrase.containsMatchIn(normalized) &&
            directEndCallCommandAtEnd.containsMatchIn(normalized)
    }

    fun isAssistantFarewell(text: String): Boolean =
        isFarewellAtEnd(normalize(text))
}

/** A farewell or explicit end-call command is actionable only after Gemini completes its reply. */
internal class GoodbyeHangupGate {
    private var goodbyePending = false
    private var assistantTurnComplete = false

    fun onCallerTranscript(text: String) {
        assistantTurnComplete = false
        goodbyePending = CallEndIntentDetector.isCallerGoodbye(text) ||
            CallEndIntentDetector.isCallerEndCallCommand(text)
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
