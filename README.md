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

When YouTube changes something, search or playback can break until NewPipe Extractor is updated and a new release is out.

## Disclaimer

### 1. Free, open source and non-commercial

Spotifry is free and open-source software, made for personal use and learning. **It is not sold and earns no money in any way:** no ads, no paid features, no subscriptions, no donations asked inside the app.

### 2. A client, not a service

Spotifry is a third-party client. It reads content that YouTube and YouTube Music already make publicly available and shows it in its own interface, the same way a web browser would. It runs entirely on your phone; there is no Spotifry server, and no account or personal data is collected.

### 3. No hosting of copyrighted material

Spotifry does not host, upload or distribute any audio, video or other copyrighted media. Music is streamed from YouTube's servers and remains the property of its copyright owners. Songs you save, and songs kept for quick replay, are stored only on your own phone for your own listening.

### 4. Support the artists

Artists, musicians and creators depend on their listeners. If you can, support them: subscribe to [YouTube Music Premium](https://www.youtube.com/musicpremium) or [Spotify Premium](https://www.spotify.com/premium/), buy their music, or go to their shows. Spotifry is a hobby project, not meant to replace these or to take income away from creators.

### 5. No affiliation

This project and its contents are not affiliated with, funded, authorized or endorsed by, or in any way associated with YouTube, Google LLC, Spotify AB, LRCLIB, or any of their affiliates and subsidiaries.

All trademarks, service marks, trade names and other intellectual property used in this project belong to their respective owners.

### 6. Use at your own risk

Spotifry is provided "as is", without warranty of any kind, as stated in the [GPL-3.0 license](LICENSE). You are responsible for how you use it and for following the terms of the services it connects to and the laws of your country.

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
