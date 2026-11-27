#!/system/bin/sh
# QuestEnhancer cleanup: after boot, if the QuestEnhancer app is gone, remove what
# its Manager installed (modules listed in $LIST) and its persist.qe.* settings,
# then this module. OS update blocking, Chats and the adb port are left alone:
# those are the user's choices, not QuestEnhancer's.
MODDIR=${0%/*}
APP=org.codeberg.anonymous950.questenhancer
DIR=/data/adb/questenhancer
LIST=$DIR/installed
LOG=$DIR/cleanup.log

for _ in $(seq 1 120); do
  [ "$(getprop sys.boot_completed)" = 1 ] && break
  sleep 5
done

# Only act on a clear answer: the package manager must answer for the system
# package first, so a slow or failed pm never reads as "uninstalled".
pm path android >/dev/null 2>&1 || exit 0
pm path $APP >/dev/null 2>&1 && exit 0

mkdir -p $DIR
echo "$(date) $APP not installed: cleaning up" >> $LOG
if [ -f $LIST ]; then
  while read -r id; do
    case "$id" in ""|*/*|.*|qe_cleanup) continue ;; esac
    rm -rf "/data/adb/modules_update/$id"
    if [ -d "/data/adb/modules/$id" ]; then
      touch "/data/adb/modules/$id/remove"
      echo "  module $id: removed at next boot" >> $LOG
    fi
  done < $LIST
  rm -f $LIST
fi
for p in $(getprop | sed -n 's/^\[\(persist\.qe\.[^]]*\)\].*/\1/p'); do
  resetprop -p --delete "$p" && echo "  prop $p: deleted" >> $LOG
done
touch $MODDIR/remove
echo "  qe_cleanup: removed at next boot" >> $LOG
