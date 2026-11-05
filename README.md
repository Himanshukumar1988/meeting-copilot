# meeting-copilot (Java)

Overlay that suggests replies and solves coding/design questions during meetings. Java 21 + Swing.

- **Hidden from screen share/recording** (macOS): `NSWindow.sharingType = none` via JNA, always-on-top, no Dock icon, controlled by global hotkeys.
- **Voice**: mic captured in 6s chunks and transcribed on-device with whisper.cpp (whisper-jni). The model (`ggml-base.en.bin`, ~148MB) downloads once into `.models/` on first mic use. Override with `STT_LOCAL_MODEL` (a ggml file name).
- **Meeting chat**: copy the chat text, press `Cmd+Shift+C`.
- **Screenshot**: `Cmd+Shift+S` (or the Snap button) opens a region selector (on macOS the system's own `screencapture -i`, which works over full-screen apps like Chrome; drag a region, Esc cancels. Set `SNAP_PICKER=builtin` for the in-app selector with resize handles) and attaches the result to the next Suggest; right-click Snap to drop it. Needs Screen Recording permission. Images go to `LLM_VISION_MODEL` (default `al-gpt-5`) because the default text model rejects images.
- **Suggest**: `Cmd+Shift+Enter` — modes Reply / Code / Design. Or type a problem and press `Cmd+Enter`.

## Run
```
cp .env.example .env        # set LLM_API_KEY
mvn package
java -jar target/meeting-copilot.jar
java -cp target/meeting-copilot.jar examples/ApiSample.java "your question"   # standalone API sample
```

## Hotkeys (Ctrl instead of Cmd off macOS)
`Cmd+Shift+Space` show/hide · `C` ingest clipboard chat · `Enter` suggest · `M` mic · `S` snap · `X` click-through

Global hotkeys need **Accessibility** permission for the terminal that launches the app (System Settings → Privacy & Security). The mic needs Microphone permission. Hotkeys are not swallowed, so the focused app also sees them.

## Known limits
- The default mic only hears you. To transcribe the other side of a call, choose **System audio (other side)** or **Mic + system audio** (lines are labelled `Me:` / `Them:`) in the dropdown above the transcript. This needs no virtual driver: a small Swift helper (ScreenCaptureKit) is compiled on first use into `.bin/` (needs Xcode command line tools) and requires *Screen & System Audio Recording* permission for the app you launch the jar from. A BlackHole/aggregate device also still works as a normal input.
- Screen-share hiding and click-through are macOS only.
- The legacy Electron/Node version (`main.js`, `src/*.js`, `renderer/`) is still in the repo and can be deleted.


mvn package
java -jar target/meeting-copilot.jar
