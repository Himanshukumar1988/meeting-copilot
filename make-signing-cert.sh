#!/bin/sh
# Creates a free self-signed code-signing certificate "Meeting Copilot Dev" in your login keychain (run once).
# package.sh signs every build with it, so the app keeps one identity and macOS remembers the
# Accessibility / Microphone / Screen Recording permissions across rebuilds.
set -e
NAME="Meeting Copilot Dev"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"

if security find-identity -v -p codesigning | grep -q "\"$NAME\""; then
  echo "\"$NAME\" already exists and is valid for code signing. Nothing to do."
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/cert.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
EOF

# /usr/bin/openssl (LibreSSL) writes a .p12 that the macOS keychain can import.
/usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$TMP/cert.cnf" \
  -keyout "$TMP/key.pem" -out "$TMP/cert.pem" 2>/dev/null
/usr/bin/openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -name "$NAME" \
  -passout pass:copilot -out "$TMP/cert.p12"

# -T lets codesign use the key without asking each time.
security import "$TMP/cert.p12" -k "$KEYCHAIN" -P copilot -T /usr/bin/codesign
# Without this, every build stops on a hidden "codesign wants to use key…" dialog (same as clicking "Always Allow").
echo "Allowing codesign to use the key without asking (enter your Mac login password)..."
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -D "$NAME" -t private "$KEYCHAIN" >/dev/null \
  || echo "    (skipped: click \"Always Allow\" the first time a build asks for the key instead)"
echo "Trusting the certificate for code signing (macOS asks for your login password)..."
security add-trusted-cert -r trustRoot -p codeSign -k "$KEYCHAIN" "$TMP/cert.pem"

security find-identity -v -p codesigning | grep "\"$NAME\"" \
  && echo "Done. ./package.sh now signs with \"$NAME\"."
