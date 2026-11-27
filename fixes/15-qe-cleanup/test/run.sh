#!/bin/sh
# Runs the cleanup module's service.sh against a fake headset (temp folders,
# stub pm/getprop/resetprop/log, a fake daemon) and checks that nothing of
# QuestEnhancer is left: run it with `sh fixes/15-qe-cleanup/test/run.sh`.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
SVC=$HERE/../module/service.sh
T=$(mktemp -d); trap 'kill $DAEMON 2>/dev/null; rm -rf "$T"' EXIT
A=$T/adb; S=$T/sdcard; B=$T/bin; mkdir -p "$A" "$S" "$B"
fail=0
check() { if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; fail=1; fi; }

cat > "$B/getprop" <<'X'
#!/bin/sh
[ "${1:-}" = sys.boot_completed ] && { echo 1; exit 0; }
[ $# -gt 0 ] && exit 0
cat "$PROPS"
X
cat > "$B/pm" <<'X'
#!/bin/sh
[ "$2" = android ] && exit 0
[ -n "${APP_PRESENT:-}" ]
X
cat > "$B/resetprop" <<'X'
#!/bin/sh
n=$(eval echo \${$#}); grep -v "^\[$n\]" "$PROPS" > "$PROPS.t"; mv "$PROPS.t" "$PROPS"
X
printf '#!/bin/sh\nexit 0\n' > "$B/log"
chmod +x "$B"/*
export PROPS=$T/props PATH="$B:$PATH" QE_ADB=$A QE_SDCARD=$S

setup() {
  rm -rf "$A" "$S"; mkdir -p "$A/modules" "$A/modules_update" "$A/questenhancer" "$S/QuestEnhancer/sounds"
  for m in questled quest1_backlight questptcolor q1_steamlink_usb qe_cleanup flashed_by_user; do
    mkdir -p "$A/modules/$m"; printf 'id=%s\n' "$m" > "$A/modules/$m/module.prop"
    printf '#!/bin/sh\ntouch "%s/modules/%s/uninstalled"\n' "$A" "$m" > "$A/modules/$m/uninstall.sh"
  done
  mkdir -p "$A/modules_update/questled" "$A/questptcolor"
  printf 'questled\nquest1_backlight\nquestptcolor\nq1_steamlink_usb\nbad/../x\n' > "$A/questenhancer/installed"
  touch "$A/questled.conf" "$A/questled.log" "$A/qebacklight.conf" "$A/.qebacklight.loaded" "$A/q1_steamlink_usb.log"
  printf '[persist.qe.library_hide]: [x]\n[persist.qe.library_sideloads]: [1]\n[persist.adb.tcp.port]: [5555]\n' > "$PROPS"
  # A daemon running from a module's folder, as questled.sh does.
  printf 'while :; do sleep 1; done\n' > "$A/modules/questled/questled.sh"
  sh "$A/modules/questled/questled.sh" & DAEMON=$!
}
# Run from the fake module's own folder, as Magisk does (it removes itself via $0).
run() { cp "$SVC" "$A/modules/qe_cleanup/service.sh"; sh "$A/modules/qe_cleanup/service.sh" >/dev/null 2>&1; sleep 1; }

echo "== app still installed: nothing changes"
setup; APP_PRESENT=1 run
check "no module flagged" '[ -z "$(find "$A/modules" -name remove)" ]'
check "lists kept" '[ -f "$A/questenhancer/installed" ]'
check "daemon still running" 'kill -0 $DAEMON 2>/dev/null'
kill $DAEMON 2>/dev/null

echo "== app uninstalled"
setup; run
for m in questled quest1_backlight questptcolor q1_steamlink_usb; do
  check "$m flagged for removal" '[ -f "$A/modules/$m/remove" ]'
  check "$m uninstall.sh ran" '[ -f "$A/modules/$m/uninstalled" ]'
done
check "pending update dropped" '[ ! -e "$A/modules_update/questled" ]'
check "daemon stopped" '! kill -0 $DAEMON 2>/dev/null'
check "leftover files deleted" '[ -z "$(ls -A "$A" | grep -v -e ^modules -e ^modules_update)" ]'
check "persist.qe props deleted" '! grep -q persist.qe "$PROPS"'
check "other props kept" 'grep -q persist.adb.tcp.port "$PROPS"'
check "empty sounds folder removed" '[ ! -e "$S/QuestEnhancer" ]'
check "user's own module untouched" '[ ! -e "$A/modules/flashed_by_user/remove" ]'
check "cleanup removes itself" '[ -f "$A/modules/qe_cleanup/remove" ]'
check "bad list entry ignored" '[ ! -e "$T/x" ]'

echo "== kept module and the user's own sounds"
setup; kill $DAEMON; echo questled > "$A/questenhancer/keep"; touch "$S/QuestEnhancer/sounds/mine.ogg"; run
check "kept module not flagged" '[ ! -e "$A/modules/questled/remove" ] && [ -f "$A/questled.conf" ]'
check "others still flagged" '[ -f "$A/modules/quest1_backlight/remove" ]'
check "user's sound kept" '[ -f "$S/QuestEnhancer/sounds/mine.ogg" ]'
check "questenhancer folder gone" '[ ! -e "$A/questenhancer" ]'

[ $fail = 0 ] && echo "RESULT PASS" || echo "RESULT FAIL"
exit $fail
