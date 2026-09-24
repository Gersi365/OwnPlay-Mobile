#!/usr/bin/env bash
set -euo pipefail

OUTPUT_AAR="${1:?usage: build_media3_ffmpeg_audio.sh <output-aar> <work-dir> <media3-source-dir> <ffmpeg-source-dir>}"
WORK_DIR="${2:?usage: build_media3_ffmpeg_audio.sh <output-aar> <work-dir> <media3-source-dir> <ffmpeg-source-dir>}"
MEDIA3_SOURCE_DIR="${3:?usage: build_media3_ffmpeg_audio.sh <output-aar> <work-dir> <media3-source-dir> <ffmpeg-source-dir>}"
FFMPEG_SOURCE_DIR="${4:?usage: build_media3_ffmpeg_audio.sh <output-aar> <work-dir> <media3-source-dir> <ffmpeg-source-dir>}"

MEDIA3_COMMIT="2bc207851df311340767e913931ca7b28cab1794"
FFMPEG_COMMIT="ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2"
NDK_VERSION="26.1.10909125"
ANDROID_ABI="26"
DECODERS=(aac mp3 ac3 eac3 truehd dca vorbis opus amrnb amrwb flac alac pcm_mulaw pcm_alaw)

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
NDK_PATH="$ANDROID_HOME/ndk/$NDK_VERSION"
test -d "$NDK_PATH"

test -d "$MEDIA3_SOURCE_DIR"
test -d "$FFMPEG_SOURCE_DIR"
test "$(git -C "$MEDIA3_SOURCE_DIR" rev-parse HEAD)" = "$MEDIA3_COMMIT"
test "$(git -C "$FFMPEG_SOURCE_DIR" rev-parse HEAD)" = "$FFMPEG_COMMIT"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" "$(dirname "$OUTPUT_AAR")"

MEDIA_DIR="$WORK_DIR/media3"
cp -a "$MEDIA3_SOURCE_DIR" "$MEDIA_DIR"

FFMPEG_DIR="$MEDIA_DIR/libraries/decoder_ffmpeg/src/main/jni/ffmpeg"
rm -rf "$FFMPEG_DIR"
cp -a "$FFMPEG_SOURCE_DIR" "$FFMPEG_DIR"
test "$(git -C "$MEDIA_DIR" rev-parse HEAD)" = "$MEDIA3_COMMIT"
test "$(git -C "$FFMPEG_DIR" rev-parse HEAD)" = "$FFMPEG_COMMIT"

FFMPEG_MODULE_PATH="$MEDIA_DIR/libraries/decoder_ffmpeg/src/main"
bash "$FFMPEG_MODULE_PATH/jni/build_ffmpeg.sh" \
  "$FFMPEG_MODULE_PATH" \
  "$NDK_PATH" \
  "linux-x86_64" \
  "$ANDROID_ABI" \
  "${DECODERS[@]}"

python3 - "$MEDIA_DIR/libraries/decoder_ffmpeg/build.gradle.kts" "$NDK_VERSION" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
ndk = sys.argv[2]
s = p.read_text()
needle = '  namespace = "androidx.media3.decoder.ffmpeg"\n'
if s.count(needle) != 1:
    raise SystemExit('unexpected decoder_ffmpeg build.gradle.kts namespace layout')
s = s.replace(needle, needle + f'  ndkVersion = "{ndk}"\n')
p.write_text(s)
PY

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$MEDIA_DIR/local.properties"
cd "$MEDIA_DIR"
./gradlew --no-daemon :lib-decoder-ffmpeg:assembleRelease

# Gradle's buildDir=buildout is project-relative. Search all module buildout
# AAR outputs and select only the artifact that contains the FFmpeg renderer.
mapfile -t AAR_CANDIDATES < <(find "$MEDIA_DIR" -type f -path '*/buildout/outputs/aar/*.aar' -print | sort)
printf 'AAR candidate: %s\n' "${AAR_CANDIDATES[@]}"
test "${#AAR_CANDIDATES[@]}" -ge 1
MATCHES=()
for candidate in "${AAR_CANDIDATES[@]}"; do
  classes="$WORK_DIR/classes-candidate.jar"
  rm -f "$classes"
  if unzip -p "$candidate" classes.jar > "$classes" 2>/dev/null \
      && jar tf "$classes" | grep -q '^androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class$'; then
    MATCHES+=("$candidate")
  fi
done
test "${#MATCHES[@]}" -eq 1
AAR="${MATCHES[0]}"
echo "Selected Media3 FFmpeg AAR: $AAR"

unzip -l "$AAR" > "$WORK_DIR/aar-contents.txt"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
  grep -Eq "jni/${abi}/libffmpegJNI\\.so$" "$WORK_DIR/aar-contents.txt"
done
unzip -p "$AAR" classes.jar > "$WORK_DIR/classes.jar"
jar tf "$WORK_DIR/classes.jar" | grep -q '^androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class$'
jar tf "$WORK_DIR/classes.jar" | grep -q '^androidx/media3/decoder/ffmpeg/FfmpegLibrary.class$'

cp "$AAR" "$OUTPUT_AAR"
sha256sum "$OUTPUT_AAR"
printf '%s\n' "${DECODERS[*]}" > "$WORK_DIR/enabled-decoders.txt"
