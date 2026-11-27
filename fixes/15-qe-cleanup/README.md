# 15: QuestEnhancer cleanup (safety net)

Android runs no code of an app when it is uninstalled, so the Manager can't
clean up after itself at that moment. This small module does it instead, so
nothing QuestEnhancer added keeps running or stays behind.

- The Manager installs it the first time it installs a module or sets a
  `persist.qe.*` prop, and writes each module it installs to
  `/data/adb/questenhancer/installed`. Modules you mark **Keep after
  uninstall** go in `/data/adb/questenhancer/keep`.
- At every boot, `service.sh` asks the package manager whether QuestEnhancer
  is still installed (only after `pm path android` answers, so a slow package
  manager never counts as "uninstalled"). If it is, nothing happens.
- If it is gone, for each listed module that isn't kept: its processes are
  stopped at once, its own `uninstall.sh` runs at once, it gets Magisk's
  `remove` flag (a pending update of it is dropped), and the files it keeps
  outside its folder (LED and backlight settings, logs, tint state) are
  deleted. Then every `persist.qe.*` prop, Vector's copy of the app's
  settings (if any), `/data/adb/questenhancer` and this module itself go.
  `/sdcard/QuestEnhancer` goes only if empty (sounds in it are your files).
  It logs to logcat (`logcat -s qe_cleanup`), so no log file is left.

Left alone on purpose: OS update blocking, Chats disabled, the adb port, kept
modules, and modules you flashed yourself. The dock's saved pins need the
hooks to shorten, so the Manager's Remove everything does that first.

`test/run.sh` runs the script against a fake headset and checks all of the
above (CI runs it). Status: passes on a PC; not yet tested on a headset.
