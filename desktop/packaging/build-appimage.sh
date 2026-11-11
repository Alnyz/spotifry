#!/usr/bin/env bash
# Builds desktop/build/appimage/Spotifry-<version>-x86_64.AppImage from the app folder Gradle makes.
# Needs a JDK with jpackage (21 or newer) and curl. The listener also needs mpv installed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../.."
cd "$root"

version="$(sed -n 's/.*packageVersion = "\(.*\)".*/\1/p' desktop/build.gradle.kts)"
./gradlew :desktop:createDistributable

app="desktop/build/compose/binaries/main/app/spotifry"
out="desktop/build/appimage"
dir="$out/AppDir"
rm -rf "$dir"; mkdir -p "$dir/usr"
cp -r "$app"/. "$dir/usr/"

cat > "$dir/AppRun" <<'RUN'
#!/bin/sh
here="$(dirname "$(readlink -f "$0")")"
exec "$here/usr/bin/spotifry" "$@"
RUN
chmod +x "$dir/AppRun"

cat > "$dir/spotifry.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Spotifry
Comment=Music from YouTube, with no ads and no skip limit
Exec=spotifry
Icon=spotifry
Categories=AudioVideo;Audio;Player;
Terminal=false
DESKTOP
cp "$here/spotifry.png" "$dir/spotifry.png"
cp "$here/spotifry.png" "$dir/.DirIcon"

tool="$out/appimagetool-x86_64.AppImage"
if [ ! -x "$tool" ]; then
  curl -L --fail -o "$tool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$tool"
fi
# --appimage-extract-and-run: works without FUSE
ARCH=x86_64 "$tool" --appimage-extract-and-run "$dir" "$out/Spotifry-$version-x86_64.AppImage"
echo "Built $out/Spotifry-$version-x86_64.AppImage"
