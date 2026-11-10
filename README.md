# meeting-copilot (Java)

Overlay that suggests replies and solves coding/design questions during meetings. Java 21 + Swing.

- **Hidden from screen share/recording** (macOS): `NSWindow.sharingType = none` via JNA, always-on-top, no Dock icon, controlled by global hotkeys.
- **Voice**: mic captured in 6s chunks and transcribed on-device with whisper.cpp (whisper-jni). The model (`ggml-base.en.bin`, ~148MB) downloads once into `.models/` on first mic use. Override with `STT_LOCAL_MODEL` (a ggml file name).
- **Speaker labels**: voices on the system audio are told apart locally (WeSpeaker model via ONNX Runtime, ~26 MB downloaded once) and labelled `Them 1`, `Them 2`, … once more than one voice is heard. It is online clustering, so it is approximate; tune with `SPEAKER_THRESHOLD` (default 0.60; higher splits voices more readily) or turn off with `SPEAKERS=off`.
- **Speaker echo filter**: with speakers instead of headphones the mic re-hears the other side. The loudness pattern of each mic chunk is compared with the system audio; a match means it is an echo and is skipped (your own voice, even over the other side, does not match). Skips are written to the log.
- **Auto-suggest**: the **Auto** button on the suggestion header drafts a reply when the other side asks a question or makes a request (12 s cooldown; skipped while you are typing).
- **Fast screenshots**: a snap is read on-device with macOS Vision (OCR) and the text goes to the fast model (about 2 s instead of 10–25 s with the vision model). If the screenshot has little text, the image itself is sent to `LLM_VISION_MODEL`. `SNAP_MODE=vision` always sends the image.
- **Translate to English**: the **→EN** button above the transcript makes speech-to-text detect the spoken language (Hindi, Spanish, French, …) and show it in English. Whisper hears the speech in its own language and the LLM translates it (much better than Whisper's own translation for Hindi/Hinglish; English lines skip the LLM). It uses a multilingual Whisper model (`ggml-small.bin`, ~470 MB, downloaded once; change it with `STT_MULTILINGUAL_MODEL`). Quality is best for major languages; use a larger model for harder ones.
- **Meeting chat**: copy the chat text, press `Cmd+Shift+C`.
- **Screenshot**: `Cmd+Shift+S` (or the Snap button) opens a region selector (on macOS the system's own `screencapture -i`, which works over full-screen apps like Chrome; drag a region, Esc cancels. Set `SNAP_PICKER=builtin` for the in-app selector with resize handles) and attaches the result to the next Suggest; right-click Snap to drop it. Needs Screen Recording permission. Images go to `LLM_VISION_MODEL` (default `al-gpt-5`) because the default text model rejects images.
- **Suggest**: `Cmd+Shift+Enter` — modes Reply / Code / Design. Or type a problem and press `Cmd+Enter`.

## Run
```
cp .env.example .env        # set LLM_API_KEY
mvn package
./run.sh                    # runs a private copy of the jar, so you can rebuild while it runs
java -cp target/meeting-copilot.jar examples/ApiSample.java "your question"   # standalone API sample
```

## Hotkeys (Ctrl instead of Cmd off macOS)
`Cmd+Shift+Space` show/hide · `C` ingest clipboard chat · `Enter` suggest · `M` mic · `S` snap · `X` click-through

Global hotkeys need **Accessibility** permission for the terminal that launches the app (System Settings → Privacy & Security). The mic needs Microphone permission. Hotkeys are not swallowed, so the focused app also sees them.

## Known limits
- The default mic only hears you. To transcribe the other side of a call, choose **System audio (other side)** or **Mic + system audio** (lines are labelled `Me:` / `Them:`) in the dropdown above the transcript. This needs no virtual driver: a small Swift helper (Core Audio process tap, macOS 14.2+) is compiled on first use into `.bin/` (needs Xcode command line tools) and requires the *System Audio Recording* permission for the app you launch the jar from. A BlackHole/aggregate device also still works as a normal input.
- Screen-share hiding and click-through are macOS only.
- The legacy Electron/Node version (`main.js`, `src/*.js`, `renderer/`) is still in the repo and can be deleted.


mvn package
java -jar target/meeting-copilot.jar

## Build a double-click app
```
./package.sh            # Apple Silicon -> dist/arm64/Meeting Copilot.app (+ dist/Meeting-Copilot-1.0.0-arm64.zip)
./package.sh x64        # Intel         -> dist/x64/Meeting Copilot.app   (+ ...-x64.zip)
./package.sh all        # both
```
Needs Maven, the Xcode command line tools, and a JDK 21 for each chip. The Intel build needs an **Intel JDK 21** (e.g. Temurin "macOS x64" from adoptium.net) given as `JAVA_HOME_X64=/path/to/jdk/Contents/Home`; an Apple Silicon Mac can build it (Rosetta runs the Intel tools). Both apps include their own Java runtime, prebuilt helpers (macOS 14.2+), and no Dock icon.

**Which Macs:** macOS 14.2 or newer (needed for system-audio capture), Apple Silicon *or* Intel. Intel Macs have no Apple GPU/Neural hardware behind Whisper, so transcription is slower there: English works, but →EN translation uses the lighter `ggml-base.bin` by default (lower accuracy, some lag); for slow Macs set `STT_LOCAL_MODEL=ggml-tiny.en.bin`.

Settings (`.env`), models and logs live in `~/Library/Application Support/Meeting Copilot`; on first run it creates a `.env` template there and tells you where to put your `LLM_API_KEY`. macOS asks for Microphone, Screen Recording and System Audio permission for the app itself, so you no longer depend on your terminal's permissions. The app is signed ad hoc by default, so **every rebuild gets a new identity and macOS forgets the permissions** (the toggles still show ON but no longer apply; hotkeys then fail with "Failed to enable access for assistive devices"). To fix a stale grant, remove the app with (−) under Privacy & Security → Accessibility / Microphone / Screen Recording / System Audio, add it again and restart it (or run `tccutil reset All com.copilot`). To make permissions survive rebuilds, create a free self-signed certificate (Keychain Access → Certificate Assistant → Create a Certificate… → Name `Meeting Copilot Dev`, Type *Code Signing*) and build with `SIGN_IDENTITY="Meeting Copilot Dev" ./package.sh all`.
