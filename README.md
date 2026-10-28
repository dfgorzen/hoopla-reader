# Read Aloud

Reads ebook pages aloud in hoopla (or any reading app) on Android/GrapheneOS.
It is an accessibility service: it reads the visible page text, speaks it with your
text-to-speech engine, turns the page with a swipe, and repeats.

## Build (GitHub Actions)

```
./setup.sh            # private repo named hoopla-reader
./setup.sh mybooks --public
```

Every push to `main` builds a signed APK and publishes it as a GitHub release.
Re-run a build any time from the Actions tab ("Run workflow").

`signing/` holds the release key and is git-ignored. Back it up: Android only
installs updates signed with the same key.

## Install on the phone

1. Install a TTS engine (F-Droid: **Sherpa TTS** for natural Piper voices, or **RHVoice**),
   then pick it in Settings → Accessibility → Text-to-speech output.
2. Download the APK from the repo's latest release and install it.
3. Open **Read Aloud** → "Turn on Read Aloud page reader".
   If the switch is greyed out: App info → ⋮ → **Allow restricted settings**, then retry.
4. Open a book in hoopla and tap ▶ on the floating control.

For automatic updates, point Obtainium at the repo
(private repos need a GitHub token in Obtainium).

## Tuning (in the app)

- **Wait after page turn**: raise it if pages get skipped or re-read.
- **Page turn**: swipe left (default) or tap the right edge.
- **Ignore top/bottom of screen**: hides headers, page numbers, progress text.
- **Apps**: package names where the control appears; the "Last app seen" line in
  the app shows the exact package name of whatever you had open.

If it says "No readable text found", the reader is drawing pages as images
rather than text; that case would need OCR added.
