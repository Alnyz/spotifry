<div align="center">

<img src=".github/assets/banner.jpg" alt="Spotifry: a simple, clean and modern music player for Android" width="100%">

[![Latest release](https://img.shields.io/github/v/release/Alnyz/spotifry?style=for-the-badge&label=download&color=5B95FF&labelColor=102038)](../../releases/latest)
[![Android 13 or newer](https://img.shields.io/badge/Android-13%2B-5B95FF?style=for-the-badge&labelColor=102038)](#download)
[![GPL-3.0 license](https://img.shields.io/badge/license-GPL--3.0-5B95FF?style=for-the-badge&labelColor=102038)](LICENSE)

[**Download**](#download) · [**Features**](#features) · [**Disclaimer**](#disclaimer) · [**Build**](#build)

</div>

> [!IMPORTANT]
> Spotifry is not affiliated with or endorsed by YouTube, Google or Spotify. Songs come from YouTube through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), straight from your phone. There is no Spotifry server in between.

---

<h2 align="center"><a id="features"></a>Features</h2>

| Feature | Description |
|---|---|
| Search and play | Any song from YouTube. The next song loads early, so skipping doesn't wait. |
| Equalizer | Your phone's ready-made sounds or your own mix, with a live picture of the music. |
| Fade between songs | The end of a song fades out and the next fades in, from 2 to 12 seconds. |
| Speed and pitch | Each set on its own, kept for every song. |
| Same loudness | Quiet songs are turned up and loud ones down. |
| Skip quiet parts | Jumps over long silent intros and endings. |
| Sleep timer | Fades the music out and stops after a set time or at the end of the song. |
| Offline | Save songs to the phone. Recently played songs (up to 500 MB) replay without using data. |
| Playlists | Search, sort, put in your own order, or shuffle. Bring in a public Spotify playlist from its link. |
| Lyrics | Follow the song as it plays, from [LRCLIB](https://lrclib.net). |
| Keeps playing | With the screen off, controlled from the lock screen and the notification. Similar songs play when the queue ends. |
| Data saver | Lower quality on mobile data. Wi-Fi stays high. |
| Theme | Light or dark, or follow the phone. |
| Updates | The app tells you when a new version is out and installs it for you. |

---

<h2 align="center"><a id="download"></a>Download</h2>

1. On your phone, open [Releases](../../releases) and download the newest `spotifry-x.y.z.apk`.
2. Open the file. Android will ask whether your browser or file manager may install apps. Allow it once.
3. From version 0.2.0 on, the app tells you when an update is out and installs it for you. Your songs and playlists stay.

Needs Android 13 or newer.

> [!NOTE]
> YouTube changes its site from time to time. When that breaks search or playback, it stays broken until NewPipe Extractor is fixed and a new Spotifry release comes out.

---

<h2 align="center"><a id="disclaimer"></a>Disclaimer</h2>

- **Free and non-commercial.** Spotifry is open-source software made for personal use and for learning. It makes no money: no ads, no paid features, no subscriptions, and no requests for donations inside the app.
- **A client, not a service.** It shows content that YouTube and YouTube Music already make public, much like a web browser does. Everything runs on your phone, and the app collects no account or personal data.
- **No hosting.** Spotifry does not host, upload or share any music or video. Songs stream from YouTube's servers and belong to their copyright owners. Songs you save, and songs kept for quick replay, stay on your own phone.
- **Support the artists.** If you can, subscribe to [YouTube Music Premium](https://www.youtube.com/musicpremium) or [Spotify Premium](https://www.spotify.com/premium/), buy their music, or go to their shows. Spotifry is a hobby project, not a replacement for these.
- **No affiliation.** Not affiliated with, funded, authorized or endorsed by YouTube, Google LLC, Spotify AB, LRCLIB, or any of their affiliates. All trademarks belong to their owners.
- **As is.** Spotifry comes without warranty of any kind, as the [GPL-3.0 license](LICENSE) says. You are responsible for how you use it and for following the terms of the services it connects to and the laws of your country.

---

## Build

```sh
./gradlew assembleRelease
```

Without a release key, the build is signed with your debug key. The release key is read from `~/keys/spotifry-keystore.properties` and never goes into this repository.

## Credits

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0) for reading YouTube
- [Media3 / ExoPlayer](https://github.com/androidx/media) for playback
- [LRCLIB](https://lrclib.net) for lyrics
- [Haze](https://github.com/chrisbanes/haze) for the glass effect
- [Albert Sans](https://github.com/usted/Albert-Sans) font (SIL Open Font License, see `licenses/`)

## License

GPL-3.0, see [LICENSE](LICENSE). Spotifry is GPL-3.0 because NewPipe Extractor, which it uses, is GPL-3.0.
