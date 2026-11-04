# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin, native Android (delegated: recommended by the assistant, accepted by the user). Audio lookup via NewPipeExtractor, playback via Media3, saved songs in Room. UI toolkit not yet decided.

## Users

One person (the owner), listening on an Android phone (Android 16). Fed up with ad breaks (about 2 minutes of ads every few songs) and Spotify's skip limit. Listens mostly to streamed music from YouTube and Spotify.

## Product Purpose

A personal, non-commercial music player: search a song, hear it within about a second, no ads, no skip limit. Songs can be saved to the phone for offline listening. Success means music plays and nothing interrupts it.

## Positioning

Plays YouTube audio directly on the phone with no server and no ads. Not for the Play Store; installed as an APK. May be open sourced later.

## Operating Context

Phone only. Used in daily listening: opening the app, searching or picking a saved song, listening with the screen off, lock-screen controls. A working spike (search, find, play in about 1 second) already ran on the owner's phone.

## Capabilities and Constraints

- Search and play, near-instant start; prepare the next song early.
- Offline saves, background playback with lock-screen controls.
- Undecided: Spotify playlist import (later), exact screen list, app name.
- Not in scope: store listing, selling, video, social features.
- Known risk: YouTube changes can break lookup until the library is updated.

## Brand Commitments

None yet. App name and logo are undecided; use a text placeholder, no invented logo. Owner asked for a modern glassmorphism look.

## Evidence on Hand

No real playlists, cover art, or song data are in the project. Mockups must use clearly fake placeholder titles, not real artist names presented as the owner's library.

## Product Principles

- Music starts fast and never gets interrupted.
- The now-playing state is always one tap away.
- Offline songs are visibly distinct from online ones.
- Nothing is shown that the app cannot do.
