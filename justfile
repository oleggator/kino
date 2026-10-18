# Android SDK resolution and the build. `just` on its own lists the recipes.
#
# Which SDK gets used, in order:
#
#   1. .sdk/ in this repo, if `just sdk` has put one there. Repo-local and deliberate,
#      so it wins over whatever is on the machine.
#   2. $ANDROID_HOME, then $ANDROID_SDK_ROOT.
#   3. ~/Android/Sdk (Android Studio's default), then /opt/android-sdk (Arch's).
#
# `just --evaluate sdk` prints the one that won. Nothing downloads an SDK behind your
# back: with none of the above present the build recipes stop and say so, and fetching
# the repo-local one is a thing you ask for by name.
#
# Why `build` is the release variant and not debug: R8 takes the APK from 16 MB to
# 2.3 MB, and debuggable=false is what makes Compose smooth on a TV. Judging Compose
# performance from a debug build measures neither. It is signed with the debug key, so
# it installs over a debug build.

portable := justfile_directory() / ".sdk"

system := if env("ANDROID_HOME", "") != "" { env("ANDROID_HOME", "") } \
     else if env("ANDROID_SDK_ROOT", "") != "" { env("ANDROID_SDK_ROOT", "") } \
     else if path_exists(home_directory() / "Android/Sdk") == "true" { home_directory() / "Android/Sdk" } \
     else if path_exists("/opt/android-sdk") == "true" { "/opt/android-sdk" } \
     else { "" }

sdk := if path_exists(portable / "platforms") == "true" { portable } else { system }

# The SDK's copy when there is one, else whatever is on PATH -- a distro
# android-tools package provides adb without providing an SDK.
adb := if path_exists(sdk / "platform-tools/adb") == "true" { sdk / "platform-tools/adb" } else { "adb" }
apk := "app/build/outputs/apk/release/app-release.apk"

# AGP 9 addresses platforms by minor version: android-37 does not satisfy android-37.0.
# Its default build-tools is tied to the AGP version, not to compileSdk.
platform := "android-37.0"
build_tools := "36.0.0"

# Any recent command-line tools build does: it is only the thing that installs the
# packages above, and those come from Google's live manifest rather than this archive.
cmdline_tools := "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"

[private]
default:
    @just --list

# Download a repo-local SDK into .sdk/ (~500 MB). Only needed with no system one.
sdk:
    #!/usr/bin/env bash
    set -euo pipefail
    sdkmanager='{{ portable }}/cmdline-tools/latest/bin/sdkmanager'

    if [ ! -x "$sdkmanager" ]; then
        tmp=$(mktemp -d)
        trap 'rm -rf "$tmp"' EXIT
        echo "fetching command-line tools -> {{ portable }}"
        curl -fsSL '{{ cmdline_tools }}' -o "$tmp/tools.zip"
        unzip -q "$tmp/tools.zip" -d "$tmp"
        mkdir -p '{{ portable }}/cmdline-tools'
        # Google's zip unpacks to cmdline-tools/; the tools insist on being under a
        # version directory, and `latest` is the one that does not go stale.
        mv "$tmp/cmdline-tools" '{{ portable }}/cmdline-tools/latest'
    fi

    # Not optional, and not inferred: run from its own bin directory the tool still
    # resolves the SDK root to ~/Android and installs into the wrong tree. There is no
    # --licenses step any more -- current tools answer that the option "is no longer
    # needed", and `yes |` in front of one now dies on SIGPIPE.
    export ANDROID_HOME='{{ portable }}'
    # ponytail: sdkmanager is a deprecated shim over the new `android` CLI and says so
    # on every run. Kept because these package coordinates are the documented ones;
    # move to `android sdk install` when the shim actually goes. Either way the install
    # goes through a tool, never a hand-unzipped archive: the tool is what writes the
    # package.xml beside each source.properties, and without that file AGP reports the
    # package missing however correct its contents are.
    "$sdkmanager" 'platform-tools' 'platforms;{{ platform }}' 'build-tools;{{ build_tools }}'

# Resolve the SDK or fail saying how to get one, and point Gradle and the IDE at it.
# Rewritten every run, so local.properties cannot go stale against the resolution above.
[private]
_sdk:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z '{{ sdk }}' ]; then
        echo "no Android SDK found." >&2
        echo "  install one and set ANDROID_HOME, or run: just sdk" >&2
        exit 1
    fi
    if [ ! -d '{{ sdk }}' ]; then
        echo "Android SDK resolved to {{ sdk }}, which does not exist." >&2
        exit 1
    fi
    echo 'sdk.dir={{ sdk }}' > local.properties

# Run the JVM unit tests.
test: _sdk
    ./gradlew test

# Build the release APK (see the note at the top on why release).
build: _sdk
    ./gradlew test assembleRelease

# Sideload the release APK onto a connected TV.
install: build
    {{ adb }} install -r {{ apk }}
