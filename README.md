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
- **Offline dictation for the whole phone.** As the assistant it is also the system recognizer,
  and it answers the microphone button of other apps (`ACTION_RECOGNIZE_SPEECH`), which nothing
  handles on GrapheneOS without Google's app.
- **Acts on the phone.** Timers, alarms, opening apps, maps and navigation, calendar events and
  text messages. Anything that sends or creates something waits for an explicit confirmation.
- **Searches the web only if you turn it on.** Off by default. When enabled, the model can query
  DuckDuckGo and read public HTTPS pages as text; no browser is opened. Remove the network
  permission and everything else still works.
- **Reads the screen on request.** Only when Android's "use text from screen" is enabled, and only
  when you tap the chip or ask about the screen ("summarise this").
- **Keeps history on the phone.** Conversations are stored in SQLite with `secure_delete`, and
  deleting one rewrites the database file so the text is really gone.
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

Then open the app, choose it as the digital assistant in Android's settings, and download a model
from the Models tab. Both GGUF and LiteRT-LM models can use the phone actions.

`docs/ESTADO.md` has the current review: what works, what was fixed, what is next and how to
verify it on the phone.

## Privacy

The app has no analytics and no server. The network permission is used only for the model
searches and downloads you start yourself, and for the web-search tool once you enable it. Speech, text and history
never leave the device.

## Licence

Apache 2.0. See `LICENSE` and `NOTICE` for the third-party components and the licences of the
models the app can download.
