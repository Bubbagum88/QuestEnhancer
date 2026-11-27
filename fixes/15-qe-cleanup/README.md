# 15: QuestEnhancer cleanup (safety net)

Android runs no code of an app when it is uninstalled, so the Manager can't
clean up after itself at that moment. This small module does it instead.

- The Manager installs it together with the first module it installs, and
  writes each module it installs to `/data/adb/questenhancer/installed`.
- At every boot, `service.sh` asks the package manager whether QuestEnhancer
  is still installed (only after `pm path android` answers, so a slow package
  manager never counts as "uninstalled"). If it is, nothing happens.
- If it is gone: every listed module gets Magisk's `remove` flag (and a
  pending update of it is dropped), every `persist.qe.*` prop is deleted, and
  this module removes itself. All of it is gone after the following restart.
  What it did is logged in `/data/adb/questenhancer/cleanup.log`.

Left alone on purpose: OS update blocking, Chats disabled, the adb port, and
modules you flashed yourself. Those are your choices; the Manager's "Remove
everything" asks about them.

The dock's saved pins are not touched (they are in SystemUX's own data); see
the README's Uninstalling section.

Status: not yet tested on a headset.
