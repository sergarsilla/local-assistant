# Local Assistant

An Android digital assistant that runs entirely on the phone. It registers as the system
assistant, so it opens as a panel over whatever app you are using, listens, answers out loud and
can act on the device — without sending anything to a server.

Built and measured on a Pixel 10 running GrapheneOS, with no dependency on Google Play services.

## What it does

- **Opens as the system assistant.** Implements `VoiceInteractionService`, so the usual assistant
  gesture (corner swipe, power button, whatever the phone is set to) brings up a panel on top of
  the current app. First frame in ~45 ms.
- **Runs your own models.** GGUF through llama.cpp and `.litertlm` through LiteRT-LM, which uses
  the GPU when the phone allows it and falls back to the CPU. Download them from Hugging Face
  inside the app, or import a file. Gemma 4 E2B answers at ~20 tokens/s on the Tensor G5's CPU.
  The system prompt and tool definitions are processed while you are still speaking.
- **Speech in and out.** Offline recognition (NVIDIA Parakeet or Canary via sherpa-onnx) and
  offline synthesis (Piper or Kokoro voices), with a conversation mode that keeps listening
  between turns. Replies are spoken with a voice of their own language.
- **Offline dictation for the whole phone.** As the assistant it is also the system recognizer;
  it answers the microphone button of other apps (`ACTION_RECOGNIZE_SPEECH`), which nothing
  handles on GrapheneOS without Google's app; and it ships a voice keyboard for any text field.
- **Optional wake word.** A 5 MB keyword spotter bundled in the APK can listen for "Hey computer"
  in the background. Off by default, with a notification while it runs.
- **Acts on the phone.** Timers, alarms, opening apps, maps and navigation, calendar events, text
  messages and calls, finding people in your contacts if you allow it. Anything that sends or
  creates something waits for an explicit confirmation. Acting reliably needs a `.litertlm`
  model; see the note below.
- **Searches the web only if you turn it on.** Off by default. When enabled, the model can query
  DuckDuckGo and read public HTTPS pages as text; no browser is opened. Remove the network
  permission and everything else still works.
- **Reads the screen on request.** Only when Android's "use text from screen" is enabled, and only
  when you tap the chip or ask about the screen ("summarise this"). With a vision model, it can
  also look at a screenshot.
- **Keeps history on the phone, if you want.** Conversations are stored in SQLite with
  `secure_delete`; deleting one rewrites the database file so the text is really gone, and saving
  can be turned off. Any conversation can be continued in the assistant.
- **Costs nothing when idle.** The model is released a few seconds after the panel closes:
  ~340 MB resident and about 10 ms of CPU per minute.

## Requirements

- Android 13 or newer, arm64.
- Android SDK with NDK 27 and CMake; JDK 17.
- A few GB free for models.

## Building

```bash
git clone --recurse-submodules <repo-url>
cd local-assistant
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use a debug build to work on the app, not to use it. Scrolling a list drops around a tenth of
its frames in debug against half a percent in release, on the same phone, because the debug
build skips R8 and carries Compose's instrumentation. For real use, copy
`keystore.properties.example` to `keystore.properties`, fill it in, and build
`./gradlew :app:assembleRelease`.

Then open the app: the Home tab walks through setting it as the assistant, the microphone and the
downloads.

## Releasing

Pushing a `v*` tag builds a signed APK and attaches it to a GitHub release. The signing key comes
from the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`;
no key material is in the repository. The workflow fails rather than publishing an APK it could
not sign, because an unsigned one installs nowhere.

## Code layout

- `assistant/` — the `VoiceInteractionService`, its session (the panel) and the recognizers.
- `llm/` — the engine interface, llama.cpp and LiteRT-LM engines, and the chat session.
- `voice/`, `keyboard/`, `wake/` — speech in and out, dictation, the voice keyboard, the wake word.
- `tools/` — what the model can do on the phone.
- `ui/` — Compose screens as Route / Screen / ViewModel on a small design system in `ui/theme` and
  `ui/components`.

## Tests

```bash
./gradlew :app:testDebugUnitTest                              # unit tests
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true # plus screenshots of every screen
```

CI builds the APK and runs both on every push; the screenshots are published as an artifact.

## What to expect

The phone is the ceiling. Android caps how much memory one app may hold - 3.1 GiB on a Pixel 10 -
and the model, the speech recogniser and the voices all come out of that. A 2B model answers at
around 10 tokens a second; a 4B one that does not fit is slowed to a crawl rather than refused,
so the Models screen offers a smaller context for models that need it.

That ceiling is also the honest limit on how clever the assistant is. Models this size get
arithmetic wrong, lose the thread of a long conversation, and sometimes answer in the wrong
language. The app can make them quick and private; it cannot make them larger.

The same ceiling decides whether a model can act rather than only talk. LiteRT-LM constrains
decoding against the tool schema, so a `.litertlm` model cannot emit a malformed call and the
phone actions work. llama.cpp only offers a lazy grammar: it keeps a call well formed once the
model has started one, but it cannot make the model start one, and small GGUF models write prose
instead — measured here with the same Gemma 4 E2B weights in both formats. Treat GGUF models as
good for chatting and unreliable for acting.

Known gaps: speaking over the assistant to interrupt it needs echo cancellation that is not
written yet, so interrupting is a tap; the Tensor NPU needs a dispatch library built separately;
and nothing here can close another app or stop its navigation, which Android does not allow.

## Privacy

The app has no analytics and no server. The network permission is used only for the model
searches and downloads you start yourself, and for the web-search tool once you enable it. Speech, text and history
never leave the device.

## Licence

Apache 2.0. See `LICENSE` and `NOTICE` for the third-party components and the licences of the
models the app can download.
