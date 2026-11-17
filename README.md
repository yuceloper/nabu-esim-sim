# Project Nabu Cellular

Experimental cellular connectivity research for Xiaomi Pad 5 (nabu).

**Status: research only — no working LTE, SIM or eSIM demonstrated.** The modem subsystem boots, but cellular QMI services have not been observed.

## Goals
- Investigate native LTE on the Wi-Fi-only Nabu board.
- Prioritize hardware-free virtual SIM/remote SIM research; assess physical SIM or eUICC only after electrical feasibility is established.
- Preserve Android/Windows dual boot, Wi-Fi and original device calibration.

## Documentation
- [Research log](docs/research-log.md) — chronological evidence and limitations
- [Architecture and hypotheses](docs/architecture.md)
- [Roadmap and next experiment](docs/roadmap.md)
- [Reproducible diagnostics](scripts/collect-readonly.sh)

## Ground rules
Read-only diagnostics first. Do not flash donor modem firmware, modify NV/EFS, bypass secure provisioning, copy subscription credentials, or enable RF transmission on unverified hardware. Only use authorized test subscriptions and legitimate provisioning. This public repository contains sanitized notes, not proprietary binaries, raw logs, device serials, SIM identifiers or keys.

Last research session: 2026-11-17.
