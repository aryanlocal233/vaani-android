# Vaani — Android

Real-time, hands-free voice-to-voice translation for Indian regional languages.
Two people speak different languages face to face; Vaani detects speech via VAD,
streams audio to a translation backend over WebSocket, and plays the translated
speech back — no push-to-talk.

Companion backend: [vaani-backend](https://github.com/aryanlocal233/vaani-backend)
(FastAPI + Bhashini ULCA STT/NMT/TTS pipeline).

## Tech Stack

- Kotlin, MVVM + Clean Architecture
- Min SDK 26 (Android 8.0), target SDK 34
- Hilt for dependency injection
- ViewBinding (no Compose)
- OkHttp WebSocket for streaming audio
- AudioRecord / AudioTrack tuned for echo cancellation

## Architecture

```
audio/          AudioRecordManager, AudioPlaybackManager, VADManager,
                 AudioChunkBuffer, AudioConfig, ConversationState,
                 TranslationForegroundService
translation/     WebSocketManager (binary protocol), TranslationOrchestrator
                 (turn-taking state machine), TranslationSession
data/            Language, LanguageRepository (12 supported languages)
di/              AppModule (Hilt)
utils/           NetworkUtils, PermissionUtils
ui/main/         MainActivity, MainViewModel
```

### Conversation state machine

```
IDLE --(speech onset)--> LISTENING
LISTENING --(400ms silence, utterance >= 1.5s)--> PROCESSING
PROCESSING --(TTS arrives)--> SPEAKING
SPEAKING --(TTS finishes)--> IDLE
SPEAKING --(speech onset / barge-in)--> cancel TTS --> LISTENING
```

Key details:
- Mic capture uses `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (hardware AEC).
- Playback uses `AudioAttributes.USAGE_VOICE_COMMUNICATION` to pair with the AEC.
- The mic is muted during TTS playback and unmuted immediately on barge-in.
- Audio is batched into 200ms chunks before being sent over the WebSocket.
- A 300ms pre-speech ring buffer is prepended to the first chunk of an utterance
  so VAD onset lag doesn't clip the start of speech.

## Binary WebSocket Protocol

Endpoint: `ws://<host>/ws/translate/{src_lang}/{tgt_lang}`

**Client → Server**

| byte[0] | Meaning          | Payload                    |
|---------|------------------|------------------------------|
| `0x00`  | Heartbeat        | none                         |
| `0x01`  | Audio chunk      | raw PCM16 mono 16kHz         |
| `0x02`  | End of utterance | final PCM chunk (may be empty) |

**Server → Client**

- Binary: `0x03` + raw PCM bytes (TTS audio)
- Text JSON: `{"type":"transcript","text":"...","final":true,"lang":"hi"}`
- Text JSON: `{"type":"translation","text":"...","lang":"ta"}`
- Text JSON: `{"type":"error","message":"..."}`

## Supported Languages

Hindi, Tamil, Telugu, Bengali, Kannada, Marathi, Gujarati, Punjabi, Malayalam,
Odia, Assamese, English (`hi` `ta` `te` `bn` `kn` `mr` `gu` `pa` `ml` `or` `as` `en`).

## Setup

1. Copy `local.properties.example` to `local.properties` and fill in:
   ```properties
   sdk.dir=/path/to/Android/sdk
   BHASHINI_API_KEY=your_key
   BHASHINI_USER_ID=your_user_id
   WS_BASE_URL=ws://10.0.2.2:8000   # emulator; use your LAN IP on a real device
   ```
2. Run the [vaani-backend](https://github.com/aryanlocal233/vaani-backend) server
   (or point `WS_BASE_URL` at a deployed instance).
3. Build and run:
   ```bash
   ./gradlew assembleDebug
   ```

## Permissions

`RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS`. The app requests mic
permission at runtime with a rationale dialog, and runs a foreground service
(with a persistent "Stop" notification) so translation keeps working while
backgrounded.
