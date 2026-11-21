package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEndIntentDetectorTest {
    @Test fun recognizesPersianFarewellPhrasesAtTheEndOfCallerTranscript() {
        listOf(
            "خداحافظ",
            "مرسی، خداحافظ!",
            "فعلاً خداحافظ",
            "خدا نگهدار",
            "خدانگهدار",
            "خدا نگه دار",
            "بدرود",
            "بای",
            "خداحافظی کنیم",
        ).forEach { assertTrue("Expected goodbye: $it", CallEndIntentDetector.isCallerGoodbye(it)) }
    }

    @Test fun recognizesEnglishFarewellPhrases() {
        listOf("Goodbye!", "bye bye", "see you later", "talk to you later").forEach {
            assertTrue("Expected goodbye: $it", CallEndIntentDetector.isCallerGoodbye(it))
        }
    }

    @Test fun normalizesArabicCharactersAndPersianJoiners() {
        assertTrue(CallEndIntentDetector.isCallerGoodbye("خدا‌نگهدار"))
        assertTrue(CallEndIntentDetector.isCallerGoodbye("خداحافظ"))
    }

    @Test fun doesNotTreatExplanatoryMentionsAsGoodbye() {
        listOf(
            "درباره خداحافظی صحبت کنیم",
            "این شعر کلمه خداحافظی دارد",
            "من معنی خداحافظی را می‌پرسم",
            "خداحافظی",
        ).forEach { assertFalse("Must not hang up: $it", CallEndIntentDetector.isCallerGoodbye(it)) }
    }

    @Test fun aWaitRequestCancelsTheGoodbyeIntent() {
        assertFalse(CallEndIntentDetector.isCallerGoodbye("نه صبر کن، خداحافظ"))
        assertFalse(CallEndIntentDetector.isCallerGoodbye("wait, don't hang up"))
    }

    @Test fun assistantFarewellCanBeDetectedFromItsFinalTranscript() {
        assertTrue(CallEndIntentDetector.isAssistantFarewell("خدانگهدار عزیزم 🌹"))
        assertTrue(CallEndIntentDetector.isAssistantFarewell("Goodbye."))
        assertFalse(CallEndIntentDetector.isAssistantFarewell("باید درباره خداحافظی صحبت کنیم"))
    }

    @Test fun callerGoodbyeWaitsForAssistantTurnCompletion() {
        val gate = GoodbyeHangupGate()
        gate.onCallerTranscript("ممنون، خداحافظ")
        assertFalse(gate.consumeReadyAfterTranscript())
        assertTrue(gate.onAssistantTurnCompleted())
        assertFalse(gate.consumeReadyAfterTranscript())
    }

    @Test fun assistantFarewellCanArriveBeforeOrAfterTurnComplete() {
        val before = GoodbyeHangupGate()
        before.onAssistantTranscript("خداحافظ")
        assertTrue(before.onAssistantTurnCompleted())

        val after = GoodbyeHangupGate()
        assertFalse(after.onAssistantTurnCompleted())
        after.onAssistantTranscript("Goodbye")
        assertTrue(after.consumeReadyAfterTranscript())
    }

    @Test fun interruptionAndContinuedCallerSpeechCancelPendingGoodbye() {
        val interrupted = GoodbyeHangupGate()
        interrupted.onCallerTranscript("خداحافظ")
        interrupted.onInterrupted()
        assertFalse(interrupted.onAssistantTurnCompleted())

        val continued = GoodbyeHangupGate()
        continued.onCallerTranscript("خداحافظ")
        continued.onCallerTranscript("نه صبر کن، یه سوال دیگه دارم")
        assertFalse(continued.onAssistantTurnCompleted())
    }

    @Test fun aNewAssistantTurnMustCompleteBeforeTheHangupGateOpens() {
        val gate = GoodbyeHangupGate()
        gate.onCallerTranscript("خداحافظ")
        gate.onAssistantTurnStarted()
        assertFalse(gate.consumeReadyAfterTranscript())
        assertTrue(gate.onAssistantTurnCompleted())
    }
}
