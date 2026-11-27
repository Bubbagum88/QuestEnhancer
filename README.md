# QuestEnhancer (fork)

> **QuestEnhancer was made by Anonymous950** (GitHub
> [Anonymous941](https://github.com/Anonymous941)). Every hook this fork builds on
> is theirs; this fork adds the Manager and the Quest 1 fixes. Thank you.

A fork of [Anonymous941/QuestEnhancer](https://github.com/Anonymous941/QuestEnhancer)
(GPL-3.0) for a rooted Oculus Quest 1 on its last firmware (v50, Android 10,
Magisk). It holds two things:

- `app/`: the Xposed module (load it with Vector or LSPosed). Upstream's hooks
  unlock hidden SystemUX features; this fork adds dock/Library hooks, casting
  fixes and blocking of Meta's app auto-updates.
- `fixes/` and `apps/`: other OS fixes for the Quest 1, each with the scripts
  that apply and undo it, working toward one OS patcher.

Decompiled Meta apps and research notes are kept separately (not public,
since the decompiled code is Meta's).

## Install QuestEnhancer

You need a rooted Quest 1 on v50 with Magisk. Download the APK from
[Releases](https://github.com/Bubbagum88/QuestEnhancer/releases) and the latest
Vector zip from [JingMatrix/Vector releases](https://github.com/JingMatrix/Vector/releases).

1. **Turn on Zygisk.** Open Magisk (Library > Unknown Sources), tap the gear,
   turn on Zygisk, and restart the headset.
2. **Install Vector.** Copy the Vector zip to the headset (SideQuest's file
   manager or `adb push`). In Magisk, open Modules > Install from storage,
   pick the zip, then restart the headset.
3. **Install QuestEnhancer.** Sideload the APK like any other app (SideQuest or
   `adb install`). If you had another QuestEnhancer build installed, uninstall
   it first: builds signed with a different key won't install over each other.
4. **Enable it in Vector.** Open Vector (Unknown Sources), go to Modules and tap
   QuestEnhancer. Turn it on with the switch at the top right. Then tap the
   checklist icon in the search bar (the leftmost of its three icons) and
   choose what the module asks for, and apply. Check that
   **Meta Quest Settings, SystemUX** and the other apps it lists really are
   ticked: Vector's filter sometimes shows every app instead, and then nothing
   is selected and the features don't appear.
5. **Give it root.** Open QuestEnhancer once. If Magisk's popup doesn't appear
   or the app says root was refused, open Magisk > Superuser and turn
   Quest Enhancer on (if it isn't listed, tap Add and pick it), then tap
   Refresh in QuestEnhancer.
6. **Restart the headset** (or `adb shell am force-stop com.oculus.systemux`).
   Hooks only load into apps started after the module was enabled.

Most of what the hooks unlock shows up in Quest's own Settings (Experimental,
Hand tracking, Developer); the QuestEnhancer app is the Manager for everything
else. To pin an Unknown Sources app to the dock, use the three dots on its tile.

**Uninstalling**

Our hooks let the dock hold up to 10 pins (no Store/Explore) and keep
sideloaded apps pinned, and SystemUX saves that pin list. Stock SystemUX reads
the whole saved list back and adds Store and Explore, so after an uninstall
the dock can overflow. So, in order:

1. In QuestEnhancer > QuestEnhancer, tap **Remove everything**. It removes the
   modules the Manager installed and its settings, puts the dock back to 5
   pins (the first 5 stay; SystemUX restarts), and asks before undoing OS
   update blocking, Chats or the adb port (left as they are by default).
2. In Vector > Modules, turn QuestEnhancer off, then restart the headset.
3. Check the dock and apps menu open, then uninstall QuestEnhancer.

If you uninstall without step 1, the cleanup module
([15-qe-cleanup](fixes/15-qe-cleanup)) removes those modules and settings at
the next boot instead. It can't shorten the dock (that needs the hooks).

If the apps menu already won't open after an uninstall, installing
QuestEnhancer again and enabling it in Vector should bring the hooks back,
then do the steps above. (Not tested yet; a factory reset is the known fix.)

**Known problems**

- UX Patcher (Lumince) hooks the same apps and stops QuestEnhancer working.
  Use one or the other.
- Something missing? Open Vector > Logs and look for
  `org.codeberg.anonymous950.questenhancer`. "Loading legacy module" there is
  normal (it is a classic Xposed module); an error after it is not.

## Fixes

Each fix has its own folder with:

- `README.md`: the problem, the root cause, what the fix changes, and how far
  it is tested (seen on a headset, or not yet).
- `apply.ps1` / `undo.ps1`: PowerShell scripts run from the folder that holds
  `adb.exe`. They take `-Device` (the headset as adb sees it, such as
  `192.168.1.50:5555`; asked for if left out) and `-Adb` (default
  `.\adb.exe`), and check their own result.

| Fix | What it does | Status |
| --- | --- | --- |
| [01-adb-fixed-port](fixes/01-adb-fixed-port) | Wireless adb always on port 5555, no pairing | Confirmed on headset, survives reboot (2026-11-05) |
| [02-chats-crashloop](fixes/02-chats-crashloop) | Stops the "Chats keeps crashing" loop | Applied on headset (disabled since 2026-10-26) |
| [03-devsettings-selinux](fixes/03-devsettings-selinux) | Android Developer options no longer crash | Confirmed on headset after reboot (2026-11-05) |
| [04-systemux-accountless](fixes/04-systemux-accountless) | Community module: dock pins any app (up to 10), Library works without a Meta account | Installed then removed 2026-11-06 (hid sideloads on an account headset); its dock/Library changes now live in this app as hooks |
| [05-boot-logo](fixes/05-boot-logo) | Your own (random) logo in the boot animation instead of the Meta logo | Boot image method confirmed on cold boot (2026-11-06); first ~2 s Meta logo is in signed XBL, can't change. Bring your own logos. |
| [06-questenhancer](fixes/06-questenhancer) | Setting up this app: Zygisk, Vector, scope | Installed 2026-11-06; Pin and 6+ dock pins confirmed, empty-dock and accountless Library untested |
| [07-cast-feedback-shim](fixes/07-cast-feedback-shim) | Zygisk shim for casting to dual-homed TVs | Hooks load (v5); fix itself not yet proven |
| [08-ocms-auto-updates](fixes/08-ocms-auto-updates) | Keeps Meta's app auto-updates off | Verified over 3 days on headset (2026-11-09) |
| [09-status-led](fixes/09-status-led) | Status LED: an effect and colour each for worn, charging, charged and asleep; settings apply live | Based on Juspertinry's q1_led (MIT); PC-tested, not yet on headset |
| [10-passthrough-tint](fixes/10-passthrough-tint) | Colour tint for the black and white passthrough | Juspertinry's q1_ptcolor (MIT), unchanged; not yet on headset |
| [11-charging-sound](fixes/11-charging-sound) | Your own plug-in sound (or any UI sound), `qesound` | Our own, after a Discord tip; not yet on headset |
| [12-backlight](fixes/12-backlight) | Holds the display brightness at a chosen value while the display is on | Our own, after a Discord tip; PC-tested, not yet on headset |
| [13-wired-steamlink](fixes/13-wired-steamlink) | Steam Link over the USB cable (USB networking), with the patched Steam Link | misteranderson91's q1_steamlink_usb (MIT), unchanged; not yet on headset |
| [14-skip-setup](fixes/14-skip-setup) | Skips first-time setup on a freshly reset rooted headset (script) | Juspertinry's method; not yet tested by us |
| [15-qe-cleanup](fixes/15-qe-cleanup) | If QuestEnhancer is uninstalled, removes the modules its Manager installed and its settings at the next boot | Our own; tested on a PC with fake folders, not yet on headset |

## Apps

| App | What it does | Status |
| --- | --- | --- |
| [devoptions](apps/devoptions) | "Developer Options" icon in the app library | Opens the page on the headset (2026-11-05) |

## Builds

GitHub Actions builds the QuestEnhancer APK, the devoptions app, the cast
shim, and packs every Magisk module under `fixes/*/module` into a zip on each
push (Actions tab). Releases are built the same way from `main`.

### Build it yourself

You need git and JDK 21 (for example Temurin 21). The Gradle wrapper fetches
Gradle itself; the Android SDK is needed too, either through Android Studio
or the command-line tools with `ANDROID_HOME` set (SDK Platform 37 and its
build tools; Gradle downloads missing SDK parts if the licences are accepted:
`sdkmanager --licenses`).

    git clone https://github.com/Bubbagum88/QuestEnhancer
    cd QuestEnhancer
    ./gradlew assembleRelease          # Windows: gradlew.bat assembleRelease

The APK is `app/build/outputs/apk/release/app-release.apk`. It is signed
with the fixed key in `app/` (`questenhancer-debug.keystore`), the same key
as the Releases and CI builds, so your build installs over a release and the
other way round. The key is public on purpose so anyone can build updates;
only install QuestEnhancer APKs from this repository or that you built yourself.
Android Studio works too: open the folder and build the `app` module.

## Credits

Every community method we use or link, with who posted it: [CREDITS.md](CREDITS.md).

- [Anonymous941](https://github.com/Anonymous941/QuestEnhancer): QuestEnhancer.
- [LucentW](https://github.com/LucentW/systemux-accountless): systemux-accountless (fix 04 fetches it; not copied here).
- [iqiyi/xHook](https://github.com/iqiyi/xHook) (MIT): used by the cast shim, fetched at build time.
- [Juspertinry/q1-modules](https://github.com/Juspertinry/q1-modules) (MIT): q1_led (fix 09 builds on it) and q1_ptcolor (fix 10, unchanged).
- [misteranderson91/quest1-steamlink-patcher](https://github.com/misteranderson91/quest1-steamlink-patcher) (MIT): q1_steamlink_usb (fix 13, unchanged); the Steam Link patcher itself is linked, not included.
- mlsplays and the Monterey Club Discord: LED colour control/cycle and Police LED ideas (fix 09; ideas only, no code).

## Licence

GPL-3.0, as upstream (see `LICENSE`).
