# Research log — 2026-11-17

## Evidence labels
Observed = directly shown in terminal output. Reported = prior session archive analysis, not revalidated in this commit. Hypothesis = not demonstrated.

| Iteration | Findings | Status |
|---|---|---|
| v0.1 | Boot properties: ro.baseband=apq, ro.radio.noril=true, rild.libpath=/vendor/lib64/libril-qc-hal-qmi.so; /dev/subsys_modem and smd nodes; IMS feature only; telephony registry radio unavailable | Observed |
| v0.2 | Modem DIAG/GLINK threads, QMI transactions, modem_a/b and modemst1/2, fsg/fsc/persist; radio HAL unavailable | Reported from archive |
| v0.3 | Magisk uid=0; qcrild init service disabled; qrtr-lookup installed; modem subsystem ONLINE | Observed |
| v0.4 | QRTR exposes DIAG 4097, EFS 21, WLAN 69, thermal and subsystem control, but no WDS 1, DMS 2, NAS 3, WMS 5, VOICE 9 or UIM 11 in snapshot | Observed |
| v0.4 | SM8150/SDM855, soc_id 339, rev 2.2; vsimapp firmware segments | Reported; firmware files independently observed in v0.9 |
| v0.5 | com.qualcomm.qti.lpa installed; no android.hardware.telephony.euicc feature | Observed |
| v0.5 | Missing LPA shared library / resource package, Xiaomi IVSIMService definition, SELinux find denial | Reported |
| v0.6 | vendor.vsimservice running, vendor.qcrild stopped, ro.vendor.radio.noril=true; UIM remote client/server components | Reported; daemon independently observed in v0.9 |
| v0.7 | vsimd maps libvsim, QSEECom API, Xiaomi HIDL; QSEE load/shutdown logs; remote SIM APK APDU/ATR/reset definitions | Reported |
| v0.8 | ELF symbols reported: is_enrolled, enroll, enroll_v3, sim_add, sim_del, sim_get, sim_list, auth, auth2. Binary transfer integrity issue reported; signatures and behavior unverified | Reported |
| v0.9 | vsimd running (PID 1421); libGPTEE_vendor.so (12400 bytes), vendor.xiaomi.hardware.mtdservice@1.0.so (80380 bytes); vsimapp files present | Observed |

## Latest firmware inventory
Path prefix: /vendor/firmware_mnt/image/

| File | Bytes |
|---|---:|
| vsimapp.b00 | 308 |
| vsimapp.b01 | 6952 |
| vsimapp.b02 | 31972 |
| vsimapp.b03 | 178 |
| vsimapp.b04 | 956 |
| vsimapp.b05 | 268 |
| vsimapp.b06 | 152 |
| vsimapp.b07 | 5427 |
| vsimapp.mdt | 7260 |

The HAL debug command output was not supplied; service registration and callable methods remain unverified. No firmware flash, soldering or enrollment is reported. Earlier archive conclusions are retained as leads rather than independently reproduced facts.
