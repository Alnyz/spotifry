#!/usr/bin/env bash
# Builds desktop/build/appimage/Spotifry-<version>-x86_64.AppImage from the app folder Gradle makes.
# Needs a JDK with jpackage (21 or newer), curl, strip (binutils) and zip. The listener also needs mpv installed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../.."
cd "$root"

version="$(sed -n 's/.*packageVersion = "\(.*\)".*/\1/p' desktop/build.gradle.kts)"
./gradlew :desktop:createReleaseDistributable

app="desktop/build/compose/binaries/main-release/app/spotifry"
out="desktop/build/appimage"
dir="$out/AppDir"
rm -rf "$dir"; mkdir -p "$dir/usr"
cp -r "$app"/. "$dir/usr/"
# smaller download: no debug symbols in the native libraries (Java's, Skia's), and only SQLite's Linux x64 part, not its
# Windows, Mac and ARM ones
find "$dir" -name '*.so' -exec strip --strip-unneeded {} +
for j in "$dir"/usr/lib/app/sqlite-bundled-jvm-*.jar; do zip -qd "$j" 'natives/linux_arm64/*' 'natives/windows_x64/*' 'natives/osx_*'; done

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
# the window's class, so a dock or taskbar can match the open window to this entry and its icon
StartupWMClass=com-spotifry-desktop-MainKt
DESKTOP
cp "$here/spotifry.png" "$dir/spotifry.png"
cp "$here/spotifry.png" "$dir/.DirIcon"

tool="$out/appimagetool-x86_64.AppImage"
if [ ! -x "$tool" ]; then
  curl -L --fail -o "$tool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$tool"
fi
# --appimage-extract-and-run: works without FUSE
# zstd at its strongest with 1 MB blocks packs about 4 MB tighter than the default
ARCH=x86_64 "$tool" --appimage-extract-and-run --mksquashfs-opt -b --mksquashfs-opt 1M --mksquashfs-opt -Xcompression-level --mksquashfs-opt 19 "$dir" "$out/Spotifry-$version-x86_64.AppImage"
echo "Built $out/Spotifry-$version-x86_64.AppImage"
