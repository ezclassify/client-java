# The Java SDK's generator and two contract tests execute Node. Compose the two
# official, digest-pinned toolchain images instead of maintaining a private
# installer or depending on a developer's home-directory shims.
FROM node:24.22.0-bookworm-slim@sha256:a5f4366e834695d5f363b920c104953a535d3a9864d1bdf74566a0ebe01daee5 AS node

FROM maven:3.10.0-eclipse-temurin-17@sha256:a6d08f0b65a5420d67425b9851283b6b6f7f49b4b3cdfb234a4203bc59784124
COPY --from=node /usr/local/ /usr/local/

RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends make \
    && rm -rf /var/lib/apt/lists/*

# The SDK calls Maven directly; Ubuntu's Pebble service supervisor is unused.
RUN rm -f /usr/bin/pebble && npm install --global npm@11.21.0 --ignore-scripts \
    && npm --prefix /usr/local/lib/node_modules/npm pkg delete devDependencies \
    && npm --prefix /usr/local/lib/node_modules/npm update --omit=dev --ignore-scripts \
         --before="$(node -p 'new Date(Date.now() - 7 * 86400000).toISOString()')"

ENV NODE_OPTIONS=--use-openssl-ca
RUN node --version \
    && npm --version \
    && mvn --version

# Native keytool removes exactly one guarded public root; keep the SDK toolchain
# context source-free and retain every other public trust anchor.
RUN <<'REVOKED_PUBLIC_ROOT'
#!/bin/bash
# Temurin's private-to-the-JDK public cacerts does not follow Ubuntu's OS update.
# Remove only the public root distrusted by Mozilla 2083449 / DSA-6572-1.
set -euo pipefail
readonly root='86:A1:EC:BA:08:9C:4A:8D:3B:BE:27:34:C6:12:BA:34:1D:81:3E:04:3C:F9:E8:A8:62:CD:5C:57:A3:6B:BE:6B'
readonly alias='cn_emsign_ecc_root_ca__g3,o_emudhra_technologies_limited,ou_emsign_pki,c_in [jdk]'
work=$(mktemp -d /tmp/public-trust.XXXXXX)
trap 'rm -rf "$work"' EXIT
keytool -list -v -cacerts -storepass changeit -J-Duser.language=en > "$work/before.txt"
grep 'SHA256:' "$work/before.txt" | sed 's/^.*SHA256: //' | sort > "$work/before"
test "$(grep -Fxc "$root" "$work/before")" = 1
grep -Fvx "$root" "$work/before" > "$work/expected"
keytool -exportcert -cacerts -storepass changeit -alias "$alias" -file "$work/revoked.der"
printf '%s  %s\n' '86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b' "$work/revoked.der" | sha256sum -c -
keytool -delete -cacerts -storepass changeit -alias "$alias"
keytool -list -v -cacerts -storepass changeit -J-Duser.language=en > "$work/after.txt"
grep 'SHA256:' "$work/after.txt" | sed 's/^.*SHA256: //' | sort > "$work/after"
test "$(cat "$work/expected")" = "$(cat "$work/after")"
REVOKED_PUBLIC_ROOT
