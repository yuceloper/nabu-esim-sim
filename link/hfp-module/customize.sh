# Sourced by Magisk installer; no system partition writes.
[ "$BOOTMODE" = true ] || abort "Install only through Magisk app."
device="$(getprop ro.product.device)"
sdk="$(getprop ro.build.version.sdk)"
[ "$device" = nabu ] || abort "This experiment is restricted to nabu."
[ "$sdk" = 33 ] || abort "This candidate is restricted to Android 13."
ui_print "- Experimental HFP client property only"
ui_print "- Does not patch Bluetooth APK, audio HAL, RIL or modem"
ui_print "- Disable/remove module and reboot to roll back"
