# Contributing

Thanks for helping with Spotifry. Bug reports, ideas and pull requests are all welcome.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting a bug

Open an [issue](../../issues/new/choose) with:

- which app: Android, Linux, Windows or Mac
- the Spotifry version (Settings → About) and your Android version or operating system
- what you did, what you expected and what happened instead
- for a song that won't play or sounds wrong, the song's name, and whether it's a download, a saved song or a stream
- for a radio station, the station's name and country
- a screenshot or short screen recording if it's something you can see

## Before you start on a change

For anything bigger than a small fix, open an issue first and say what you plan to do. It saves you from building something that clashes with work already under way. A new look for a screen is easiest to agree on as a picture first, before any code.

## Building and testing

The Android app, the desktop app and the code they share are one Gradle project. In short:

```sh
./gradlew :app:assembleDebug          # the Android app
./gradlew :desktop:run                # the desktop app (needs mpv installed)
./gradlew :shared:test :app:testDebugUnitTest :desktop:test
```

The desktop app plays music with mpv, so install it first. The Android tests run on your computer and need no phone. If the tests fail with a message about loading a native library, your `/tmp` may be full; point `java.io.tmpdir` at another folder.

To try a change on a phone, install a debug build with `adb install`. Don't clear the app's data on a phone that has your own music saved.

All tests must pass before a pull request is merged. A change to how something behaves should come with a test that fails without the change.

## Where things live

- `app/`: the Android app (Jetpack Compose). Screens are in `ui/`, playback in `player/`.
- `desktop/`: the Linux, Windows and Mac app (Compose Desktop, playing through mpv).
- `shared/`: plain Kotlin both apps use: songs, search sources, playlists, settings, radio. Keep it free of Android and desktop code, so both can use it.
- `site/`: the website.

When a feature exists on both the phone and the desktop, put the logic in `shared/` and keep only the screens in `app/` and `desktop/`.

## Things to keep in mind

- **No ads, no accounts.** Spotifry has no server of its own. Anything that sends data off the device must send as little as possible, have a switch in Settings and be described in the README: what is sent, to whom, and how to turn it off.
- **Older phones.** The Android app supports Android 7 and newer. Before using a newer Android call, check it's available there (`./gradlew :app:lintRelease` reports these as `NewApi`).
- **Light on the phone.** Don't make screens redraw when nothing changes. Blur is only used on the mini player and the Now playing panel.
- **Plain words.** Text in the app is short and says what happens, with no jargon. Comments in code say why something is done, in plain words. Match the style of the code around your change.

## Commits and pull requests

Commit messages start with a lowercase type and say what changes for the person using the app:

```
fix: the cover fade on playlist and artist pages starts lower
feat: radio page with live stations and nonstop mixes
```

Types: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `chore`, `ci`.

Keep one topic per pull request. In the description, say:

- **What** changes, as the person using the app would see it
- **Why** it's needed
- **Testing**: how you checked it, which tests you added, and screenshots for anything visual

## License

Spotifry is released under the [GPL-3.0](LICENSE), because NewPipe Extractor, which it uses, is GPL-3.0. By contributing you agree that your work is released under the same license.
