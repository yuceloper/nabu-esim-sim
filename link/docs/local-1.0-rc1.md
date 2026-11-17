# Nabu Link Local Edition 1.0 RC1 (unverified build)

Scope: Redmi Note 8 (ginkgo) hosts its real SIM; Mi Pad 5 (nabu) is client. Remote relay / internet-wide access intentionally excluded. LAN or manual phone hotspot, saved encrypted pairing, modem status, SMS send/receive and call control. Previous user-confirmed milestones: v0.2 encrypted modem status, v0.4 live update, v0.5 hotspot operation, v0.6 SMS. v0.7 call control and all RC changes await on-device verification. No claim of production/security certification.

## Build
From repository root on Mac:
```sh
git switch feature/nabu-link
git pull --ff-only origin feature/nabu-link
bash scripts/build-link-apk.sh
bash scripts/build-link-hfp-module.sh
```
First command builds debug-signed APK with existing local keystore (retain it). Second packages experimental nabu-only HFP property module; it does not install or enable anything. Neither is a production release. Do not install an old APK after a failed build. Install the same APK on both devices when testing resumes.

## Local app
* Previously paired devices retain keys; do not forget unless troubleshooting. SMS and call permissions explicitly enabled on phone.
* Recent SMS history: separate opt-in and READ_SMS permission, plus SMS sharing permission. User requests latest eight inbox/sent entries explicitly; truncates bodies to 400 characters and addresses to 100; no permanent tablet archive. Clear view or revoke access. Android installer/ROM may restrict READ_SMS. Contents not logged.
* Android ACTION_DIAL (tel) and ACTION_SENDTO (sms/smsto) handlers allow Nabu Link to be selected from other apps. These only prefill validated drafts, never automatically execute a call/message. No default-dialer or default-SMS role claimed. Existing HyperOS Phone/Messages are not patched; no virtual RIL/SIM emulation.
* Phone Bluetooth route monitor: user grants nearby-device permission on supported Android; public BluetoothHeadset API observes SCO activity. It cannot prove the audio peer is the tablet and does not capture audio. Enable monitor on phone after starting host; open platform Bluetooth settings to pair manually. Call controls remain encrypted over Wi-Fi.
* No remote backend, cloud storage, root-dependent APK permissions, or automatic hotspot management. No audio recording or forwarding over TCP.

## Experimental HFP client activation (separate)
Normal Android apps cannot capture carrier call audio or inject a remote microphone into the modem using standard SDK. Prefer conventional Bluetooth Hands-Free Profile, phone = AG, tablet = HF, SCO linking audio. AOSP Android 13 Automotive sets bluetooth.profile.hfp.hf.enabled=true. The nabu-only Magisk experiment applies ONLY that property; no audio policy, SELinux, modem, APEX or boot image patch. It is not evidence the vendor Bluetooth stack implements the HF role. AOSP also has HfpClientConnectionService resource control, and routing may need additional client-side controls. Do not enable blindly; first probe stock device when tests resume:
```sh
bash scripts/probe-link-hfp.sh YOUR_TABLET_ADB_SERIAL
```
Probe displays a few known props and counts of service/audio markers, not full dumps or addresses; zero counts can also mean restricted dumps. If native HF works, standard Bluetooth pairing and call route may support bidirectional audio. If not, inspect actual Bluetooth APK and audio HAL offline before an overlay/system change. Property module is restricted to nabu SDK33; optional manual installation only after diagnostics and recovery plan. Rollback: disable/remove module in Magisk then reboot; if needed from a working root shell, create /data/adb/modules/nabulink_hfp/disable and reboot. No module installed by these commits.
 
## Deferred validation
Build both artifacts; verify installed version. Regression test status, pairing, Wi-Fi/hotspot transition, SMS send/receive/delivery distinction and history permission revocation; ordinary outgoing and incoming call, answer/decline/end, lost connection, negative commands. Test HFP probe before optional module, then Bluetooth connection and both-way speech on ordinary calls with consent. Test native intent handlers without automatic dialing and check role/default app unchanged. Test screen-off and battery management; no automatic reboot start. No emergency calls. No remote mode. Full native app integration, actual two-way audio and instant hotspot remain gated by ROM evidence; not release-qualified.

## References
https://developer.android.com/media/platform/sharing-audio-input
https://developer.android.com/reference/android/bluetooth/BluetoothHeadset
https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android13-release/car_product/properties/bluetooth.prop
https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-release/android/app/res/values/config.xml
https://source.android.com/docs/automotive/ivi_connectivity
https://github.com/topjohnwu/Magisk/blob/master/docs/guides.md
