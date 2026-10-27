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
- **Build interpreter path** is machine-local and must **not** be committed. Configure it with
  a Gradle property (command line or `~/.gradle/gradle.properties`) or an environment variable:

  ```sh
  ./gradlew assembleDebug -Psocialdl.python.buildPython=C:\path\to\python.exe
  # or
  SOCIALDL_PYTHON=C:/path/to/python.exe ./gradlew assembleDebug
  ```

  Empty/unset (or a path that doesn't exist) → Chaquopy falls back to `python` on PATH.

### Supported ABIs

Only `arm64-v8a` is built and shipped. The `armeabi-v7a`/`x86`/`x86_64` Node and FFmpeg
libraries were Git LFS pointers, not ELF binaries, so they were removed. Re-add an ABI to
`abiFilters` (in `:app` and `:core:python`) only after real, tested binaries exist; CI
rejects an APK whose packaged `.so` files are not ELF.

### Tests

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
python -m unittest discover -s python/tests -v
```
- **pip dependencies** are pinned once in `python/requirements.txt`, next to the bridge
  sources, and read by `:core:python`'s `pip {}` block. Update versions there only.

### Room schema exports

`core/database` builds with `exportSchema = true` into `core/database/schemas/`. Those JSON
files **must be committed** with any entity change — they drive future real migrations and
are NOT gitignored.
