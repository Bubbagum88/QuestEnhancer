#!/system/bin/sh
# Stop the daemon and hand brightness back to the system. qebacklight.conf
# stays, so settings survive a reinstall (QuestEnhancer's cleanup deletes it
# if QuestEnhancer itself is uninstalled).
pkill -f backlightd.sh 2>/dev/null
rm -f /data/adb/.qebacklight.loaded
exit 0
