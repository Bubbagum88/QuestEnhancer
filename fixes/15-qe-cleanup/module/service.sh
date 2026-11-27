#!/system/bin/sh
# QuestEnhancer cleanup: after boot, if the QuestEnhancer app is gone, remove
# everything its Manager added and leave nothing running or behind:
#  - each module it installed ($LIST), unless the user chose to keep it ($KEEP):
#    its processes are stopped now, its own uninstall.sh runs now (Magisk runs
#    it again when it deletes the module at the next boot), its leftover files go;
#  - every persist.qe.* prop;
#  - Vector's copy of the app's settings, if Vector left one;
#  - /data/adb/questenhancer, and this module itself.
# OS update blocking, Chats and the adb port are left alone (the user's choices),
# and so are modules the user flashed themselves. Logs go to logcat (tag
# qe_cleanup), so no log file is left either.
MODDIR=${0%/*}
APP=org.codeberg.anonymous950.questenhancer
ADB=${QE_ADB:-/data/adb}
DIR=$ADB/questenhancer
LIST=$DIR/installed
KEEP=$DIR/keep
SDCARD=${QE_SDCARD:-/sdcard}

say() { log -t qe_cleanup "$*" 2>/dev/null; echo "$*"; }

for _ in $(seq 1 120); do
  [ "$(getprop sys.boot_completed)" = 1 ] && break
  sleep 5
done

# Only act on a clear answer: the package manager must answer for the system
# package first, so a slow or failed pm never reads as "uninstalled".
pm path android >/dev/null 2>&1 || exit 0
pm path $APP >/dev/null 2>&1 && exit 0

say "$APP is not installed: cleaning up"

# Files a module leaves outside its own folder on purpose (kept across its own
# reinstall). Gone with QuestEnhancer.
leftovers() {
  case "$1" in
    questled) echo "$ADB/questled.conf $ADB/questled.log" ;;
    quest1_backlight) echo "$ADB/qebacklight.conf $ADB/.qebacklight.loaded" ;;
    questptcolor) echo "$ADB/questptcolor" ;;
    q1_steamlink_usb) echo "$ADB/q1_steamlink_usb.log" ;;
  esac
}

# Stops every process started from a module's folder (daemons, loops).
stop_procs() {
  for c in /proc/[0-9]*/cmdline; do
    tr '\0' ' ' < "$c" 2>/dev/null | grep -q "$ADB/modules/$1/" || continue
    p=${c#/proc/}; p=${p%/cmdline}
    [ "$p" = "$$" ] || kill "$p" 2>/dev/null
  done
}

if [ -f "$LIST" ]; then
  while read -r id; do
    case "$id" in ""|*/*|.*|qe_cleanup) continue ;; esac
    if [ -f "$KEEP" ] && grep -qx "$id" "$KEEP"; then
      say "module $id: kept (chosen in QuestEnhancer)"
      continue
    fi
    rm -rf "$ADB/modules_update/$id"
    m=$ADB/modules/$id
    if [ -d "$m" ]; then
      stop_procs "$id"
      [ -f "$m/uninstall.sh" ] && sh "$m/uninstall.sh" >/dev/null 2>&1
      touch "$m/remove"
      say "module $id: stopped, removed at next boot"
    fi
    for f in $(leftovers "$id"); do rm -rf "$f"; done
  done < "$LIST"
fi

# The charging sound's folder: the sounds in it are the user's own files, so it
# goes only if empty.
rmdir "$SDCARD/QuestEnhancer/sounds" "$SDCARD/QuestEnhancer" 2>/dev/null

for p in $(getprop | sed -n 's/^\[\(persist\.qe\.[^]]*\)\].*/\1/p'); do
  resetprop -p --delete "$p" && say "prop $p: deleted"
done

for d in /data/misc/*/prefs/$APP; do
  [ -d "$d" ] && rm -rf "$d" && say "Vector's settings copy deleted"
done

rm -rf "$DIR"
touch "$MODDIR/remove"
say "qe_cleanup: removed at next boot; nothing of QuestEnhancer is left"
