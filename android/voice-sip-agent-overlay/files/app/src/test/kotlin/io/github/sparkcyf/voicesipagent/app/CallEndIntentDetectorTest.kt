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

    @Test fun recognizesDirectPersianEndCallCommands() {
        listOf(
            "قطع کن",
            "لطفاً قطع کن",
            "تماس رو قطع کن",
            "تماس را قطع کن",
            "مکالمه رو تموم کن",
            "مکالمه را تمام کن",
            "تماس رو ببند",
            "به مکالمه پایان بده",
            "دیگه صحبت نکنیم",
        ).forEach {
            assertTrue("Expected end-call command: $it", CallEndIntentDetector.isCallerEndCallCommand(it))
        }
    }

    @Test fun recognizesEnglishEndCallCommands() {
        listOf("hang up", "hang up the phone", "end the call", "end this call", "disconnect the call").forEach {
            assertTrue("Expected end-call command: $it", CallEndIntentDetector.isCallerEndCallCommand(it))
        }
    }

    @Test fun doesNotTreatUnrelatedOrNegatedCutCommandsAsCallEnd() {
        listOf(
            "صدای موسیقی رو قطع کن",
            "این ویدیو رو قطع کن",
            "تماس رو قطع نکن",
            "هنوز قطع نکن",
            "من معنی قطع کردن تماس رو می‌پرسم",
        ).forEach {
            assertFalse("Must not end call: $it", CallEndIntentDetector.isCallerEndCallCommand(it))
        }
    }

    @Test fun explicitEndCallCommandWaitsForGeminiTurnCompletion() {
        val gate = GoodbyeHangupGate()
        gate.onCallerTranscript("قطع کن")
        assertFalse(gate.consumeReadyAfterTranscript())
        assertTrue(gate.onAssistantTurnCompleted())
        assertFalse(gate.consumeReadyAfterTranscript())
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

        val commandInterrupted = GoodbyeHangupGate()
        commandInterrupted.onCallerTranscript("قطع کن")
        commandInterrupted.onCallerTranscript("نه صبر کن، یه سؤال دیگه دارم")
        assertFalse(commandInterrupted.onAssistantTurnCompleted())
    }

    @Test fun aNewAssistantTurnMustCompleteBeforeTheHangupGateOpens() {
        val gate = GoodbyeHangupGate()
        gate.onCallerTranscript("خداحافظ")
        gate.onAssistantTurnStarted()
        assertFalse(gate.consumeReadyAfterTranscript())
        assertTrue(gate.onAssistantTurnCompleted())
    }
}
