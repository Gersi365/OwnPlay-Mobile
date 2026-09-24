# Media3 FFmpeg audio decoder provenance

OwnPlay includes an audio-only Media3 FFmpeg decoder extension so Live/VOD playback can fall back to software decoding when the Android device does not expose a compatible MediaCodec decoder.

## Pinned inputs

- AndroidX Media3 version: `1.11.0`
- AndroidX Media3 commit: `2bc207851df311340767e913931ca7b28cab1794`
- FFmpeg version: `6.0`
- FFmpeg commit: `ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2`
- Android NDK: `26.1.10909125`
- Native API level: `26` (matches OwnPlay minSdk)
- AAR: `app/libs/media3-decoder-ffmpeg-1.11.0-ffmpeg6.0.aar`
- AAR SHA-256: `3997eab5910483a4b7ab2928def2894379b2d9664ac54291c488669380f98734`

## Enabled audio decoders

`aac mp3 ac3 eac3 truehd dca vorbis opus amrnb amrwb flac alac pcm_mulaw pcm_alaw`

Media3 maps `mp3` to MPEG Layer I/II/III audio and `dca` to DTS-family audio. No FFmpeg video decoder is enabled in this artifact.

## Runtime selection

OwnPlay keeps `DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON`. Android MediaCodec remains preferred; `FfmpegAudioRenderer` is selected only when the platform audio renderer does not support the input format. Decoder fallback remains enabled.

## Rebuild

Run `tools/build-media3-ffmpeg-audio.sh <output-aar> <work-dir> <media3-source-dir> <ffmpeg-source-dir>` on Linux with Android SDK 36, NDK 26.1.10909125 and CMake available. Provide local source checkouts matching the pinned Media3 and FFmpeg identities above. The script verifies those identities before building and checks that all four ABI JNI libraries plus `FfmpegAudioRenderer` are present.

## Licensing

The AndroidX Media code is Apache-2.0. FFmpeg is separately licensed. This FFmpeg build does not enable GPL/nonfree options and packages the upstream FFmpeg license texts under `app/src/main/assets/licenses/ffmpeg/`. Public distribution remains subject to the applicable FFmpeg/LGPL obligations and should pass the project's release/legal review gate.
