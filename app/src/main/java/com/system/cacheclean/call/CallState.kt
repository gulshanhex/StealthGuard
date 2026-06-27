package com.system.cacheclean.call

/**
 * CallState
 *
 * All possible states of the fake call engine.
 * FakeCallActivity observes state changes to update UI accordingly.
 *
 * State transition diagram:
 *
 *   IDLE ──start()──► HOOK ──playback done──► LISTEN
 *                                               │
 *                          ┌────────────────────┤
 *                          │                    │
 *                     keyword matched      timeout / no match
 *                          │                    │
 *                          ▼                    ▼
 *                       RESPOND             FILLER
 *                          │                    │
 *                          └────────────────────┘
 *                                    │
 *                             playback done
 *                                    │
 *                                    ▼
 *                                 LISTEN  (loop)
 *
 *   Any state ──panicCancel()──► ENDED
 */
enum class CallState {
    /** Initial state before start() is called. */
    IDLE,

    /** Playing the opening hook audio clip. */
    HOOK,

    /** Microphone is active. SpeechRecognizer is listening. 3s timeout armed. */
    LISTEN,

    /** A keyword was matched. Playing the corresponding response audio. */
    RESPOND,

    /** No keyword matched or STT timed out. Playing a random filler clip. */
    FILLER,

    /** Call was cancelled (panic cancel, decline, end button). All resources released. */
    ENDED
}
