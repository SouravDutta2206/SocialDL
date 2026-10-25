# SocialDL

Android downloader app (Kotlin, Compose, Hilt, Chaquopy/yt-dlp, FFmpeg, Node).

## Build

```sh
./gradlew assembleDebug
```

### Chaquopy / Python build configuration

- **`:core:python` is the single Chaquopy owner** (plugin, python source dir, pip deps). The
  `:app` module depends on it and gets the merged Python assets transitively. Do not re-apply
  the `com.chaquo.python` plugin in other modules.
- **Build interpreter path** is machine-local and configured via `gradle.properties`:

  ```properties
  socialdl.python.buildPython=C:\path\to\python.exe
  ```

  Empty/unset (or a path that doesn't exist) → Chaquopy falls back to `python` on PATH.
- **pip dependencies** are pinned once in `python/requirements.txt`, next to the bridge
  sources, and read by `:core:python`'s `pip {}` block. Update versions there only.

### Room schema exports

`core/database` builds with `exportSchema = true` into `core/database/schemas/`. Those JSON
files **must be committed** with any entity change — they drive future real migrations and
are NOT gitignored.
