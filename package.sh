#!/bin/sh
# Builds "Meeting Copilot.app" (a double-clickable macOS app with its own Java runtime).
#
#   ./package.sh            Apple Silicon (arm64) build  -> dist/arm64/Meeting Copilot.app
#   ./package.sh x64        Intel build                  -> dist/x64/Meeting Copilot.app
#   ./package.sh all        both
#
# Needs: Maven, the Xcode command line tools (Swift), and a JDK 21 for each chip you build for:
#   arm64: the JDK on your PATH (or JAVA_HOME_ARM64)
#   x64:   an Intel JDK 21, e.g. Temurin "macOS x64" from https://adoptium.net, passed as JAVA_HOME_X64=/path/to/Contents/Home
#          (an Apple Silicon Mac can build it; Rosetta runs the Intel tools)
# Optional: SIGN_IDENTITY="Meeting Copilot Dev" keeps one stable signature so macOS remembers permissions.
set -e
cd "$(dirname "$0")"
APP="Meeting Copilot"
VERSION="1.0.0"
TARGETS="${1:-arm64}"
[ "$TARGETS" = all ] && TARGETS="arm64 x64"

echo "==> Building the jar (shared by both chips)"
mvn -q clean -DskipTests package

build_one() {
  ARCH="$1"
  case "$ARCH" in
    arm64) SWIFT_TARGET="arm64-apple-macos14.2"
           JPACKAGE="${JAVA_HOME_ARM64:+$JAVA_HOME_ARM64/bin/}jpackage" ;;
    x64)   SWIFT_TARGET="x86_64-apple-macos14.2"
           [ -n "$JAVA_HOME_X64" ] || { echo "Set JAVA_HOME_X64 to an Intel (x64) JDK 21 home directory." >&2; exit 1; }
           JPACKAGE="$JAVA_HOME_X64/bin/jpackage" ;;
    *) echo "Unknown target '$ARCH' (use arm64, x64 or all)" >&2; exit 1 ;;
  esac
  STAGE="build/app-input-$ARCH"
  OUT="dist/$ARCH"

  echo "==> [$ARCH] Building the native helpers (macOS 14.2 or newer)"
  rm -rf "$STAGE" "$OUT" && mkdir -p "$STAGE" "$OUT"
  cp target/meeting-copilot.jar "$STAGE/"
  for h in system-audio ocr; do
    swiftc -O -swift-version 5 -target "$SWIFT_TARGET" "src/main/resources/$h.swift" -o "$STAGE/$h"
  done

  echo "==> [$ARCH] Packaging the app"
  "$JPACKAGE" --type app-image --name "$APP" --dest "$OUT" \
    --input "$STAGE" --main-jar meeting-copilot.jar --main-class com.copilot.Main \
    --add-modules java.desktop,java.logging,java.net.http,java.management,java.naming,java.prefs,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.charsets \
    --java-options "-Dapple.awt.UIElement=true" \
    --app-version "$VERSION"

  # macOS asks for permission using these texts. Editing Info.plist invalidates the signature, so sign again.
  PLIST="$OUT/$APP.app/Contents/Info.plist"
  add() { /usr/libexec/PlistBuddy -c "Add :$1 string $2" "$PLIST" 2>/dev/null || /usr/libexec/PlistBuddy -c "Set :$1 $2" "$PLIST"; }
  add NSMicrophoneUsageDescription "Meeting Copilot transcribes your voice during meetings, on this Mac."
  add NSAudioCaptureUsageDescription "Meeting Copilot transcribes the other side of your calls, on this Mac."
  add NSScreenCaptureUsageDescription "Meeting Copilot captures the area of the screen you select to answer questions about it."
  /usr/libexec/PlistBuddy -c "Add :LSUIElement bool true" "$PLIST" 2>/dev/null || true
  # The overlay is never the frontmost app, so stop macOS from throttling it ("App Nap") while it transcribes.
  /usr/libexec/PlistBuddy -c "Add :NSAppSleepDisabled bool true" "$PLIST" 2>/dev/null || true

  # Ad-hoc signing ("-") gives every build a new identity, so macOS forgets the permissions you granted. Set
  # SIGN_IDENTITY to a certificate name to keep one stable identity across rebuilds (see the README).
  # Uses "Meeting Copilot Dev" automatically when it exists (create it once with ./make-signing-cert.sh).
  if [ -z "$SIGN_IDENTITY" ] && security find-identity -v -p codesigning | grep -q '"Meeting Copilot Dev"'; then
    SIGN_IDENTITY="Meeting Copilot Dev"
  fi
  SIGN="${SIGN_IDENTITY:--}"
  codesign --force --deep --sign "$SIGN" "$OUT/$APP.app"
  [ "$SIGN" = "-" ] && echo "    NOTE: ad-hoc signed. After each rebuild, remove and re-add the app under Privacy & Security." \
    "Run ./make-signing-cert.sh once to fix this permanently."

  # ditto keeps the signature intact (a plain Finder "Compress" can break it)
  ditto -c -k --sequesterRsrc --keepParent "$OUT/$APP.app" "dist/Meeting-Copilot-$VERSION-$ARCH.zip"
  echo "==> [$ARCH] Done: $OUT/$APP.app  and  dist/Meeting-Copilot-$VERSION-$ARCH.zip"
}

for t in $TARGETS; do build_one "$t"; done
echo "Drag the app to Applications. Settings and downloaded models live in ~/Library/Application Support/Meeting Copilot"
