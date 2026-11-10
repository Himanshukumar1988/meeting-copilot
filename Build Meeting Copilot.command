#!/bin/sh
# Double-click in Finder: rebuilds Meeting Copilot and installs it into /Applications.
# Permissions survive rebuilds because every build is signed with the "Meeting Copilot Dev" certificate.
cd "$(dirname "$0")" || exit 1

# Maven and JDK 21 installed under ~/.local/opt (Finder does not load your shell's PATH).
JDK="$(ls -d "$HOME"/.local/opt/jdk-21*/Contents/Home 2>/dev/null | tail -1)"
MVN="$(ls -d "$HOME"/.local/opt/apache-maven-*/bin 2>/dev/null | tail -1)"
[ -n "$JDK" ] && export JAVA_HOME="$JDK" && export PATH="$JDK/bin:$PATH"
[ -n "$MVN" ] && export PATH="$MVN:$PATH"

fail() { echo; echo "FAILED: $1"; echo "Press Return to close."; read -r _; exit 1; }
command -v mvn >/dev/null || fail "Maven not found (expected under ~/.local/opt/apache-maven-*)."
command -v jpackage >/dev/null || fail "JDK 21 not found (expected under ~/.local/opt/jdk-21*)."

# One-time: the signing certificate that keeps permissions across rebuilds.
security find-identity -v -p codesigning | grep -q '"Meeting Copilot Dev"' || ./make-signing-cert.sh || fail "could not create the signing certificate."

if [ "$(uname -m)" = arm64 ]; then ARCH=arm64; else ARCH=x64; export JAVA_HOME_X64="$JAVA_HOME"; fi
./package.sh "$ARCH" || fail "build failed (see above)."

echo "==> Installing into /Applications"
osascript -e 'quit app "Meeting Copilot"' 2>/dev/null
sleep 1
rm -rf "/Applications/Meeting Copilot.app"
ditto "dist/$ARCH/Meeting Copilot.app" "/Applications/Meeting Copilot.app" || fail "could not copy into /Applications."
codesign --verify --deep --strict "/Applications/Meeting Copilot.app" || fail "the installed app's signature is invalid."

echo "==> Done. Opening Meeting Copilot."
open "/Applications/Meeting Copilot.app"
