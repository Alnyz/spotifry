# Spotifry

A small Android music player. Search a song, it plays in about a second: no ads, no skip limit, no account.

Music comes from YouTube through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor). There is no server: the app talks to YouTube directly from your phone.

## Install

1. Open [Releases](../../releases) on your phone and download the latest `spotifry-x.y.z.apk`.
2. Open the file. Android asks to allow installing from your browser or file manager: allow it once.
3. Updates: download the new APK and open it. Your songs and playlists stay.

Needs Android 13 or newer.

## What it does

- Search songs and play them at once; the next song is prepared early so skipping is instant.
- Plays with the screen off, with lock-screen and notification controls.
- Save songs to the phone for offline listening. Songs you've played recently are also kept (up to 500 MB) so replays start at once and use no data.
- Playlists, and import of a public Spotify playlist from its link (first 100 songs, matched on YouTube).
- When the queue runs out, similar songs keep playing.
- Recently played, most played, and picks up where you left off after the app was closed.
- Timed lyrics from [LRCLIB](https://lrclib.net).
- Sleep timer that fades the music out.
- Same loudness for every song (can be turned off).
- Data saver for mobile data, light and dark theme.

## Good to know

- When YouTube changes something, search or playback can break until NewPipe Extractor is updated and a new release is out.
- Not affiliated with Spotify, YouTube or Google.

## Build

```sh
./gradlew assembleRelease
```

Without a release key the build is signed with your debug key. The release key is read from `~/keys/spotifry-keystore.properties` and is never part of this repository.

## Credits

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0) for reading YouTube
- [Media3 / ExoPlayer](https://github.com/androidx/media) for playback
- [LRCLIB](https://lrclib.net) for lyrics
- [Haze](https://github.com/chrisbanes/haze) for the glass effect
- [Albert Sans](https://github.com/usted/Albert-Sans) font (SIL Open Font License, see `licenses/`)

## License

GPL-3.0, see [LICENSE](LICENSE). Spotifry uses NewPipe Extractor, which is GPL-3.0, so the app is too.
