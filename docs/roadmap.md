# Roadmap

## Current milestone: v0.9 — read-only baseline

- [x] Root and modem subsystem state
- [x] QRTR snapshot and missing cellular services
- [x] LPA package and VSIM daemon presence
- [ ] Verify HAL registration/debug output
- [ ] Reacquire native binaries with byte-for-byte hash validation (local only)
- [ ] Resolve VSIM interface signatures offline
- [ ] Design nabu-vsim-cli v0.1: service presence and non-sensitive capability/status queries only
- [ ] Determine why cellular QMI is absent
- [ ] Board-level RF/UIM feasibility review
- [ ] If supported, authorized SIM/eSIM provisioning experiment
- [ ] If safe RF path confirmed, LTE registration/data; SMS/voice later
- [ ] Consider Windows integration after Android proof of concept

## Next run: v0.10

Run scripts/collect-readonly.sh from Mac with adb and Magisk authorization. It records service registry, process status, QRTR and local binary hashes; review locally, redact identifying data before sharing. Compare against v0.3 and v0.9. A missing HAL listing is not proof of absent service if access is denied. Do not change SELinux, properties, radio state or firmware for this run.

Only after interface signatures are verified should a CLI be implemented. First acceptance: report service reachable/unreachable without mutation. No enrollment, authentication or profile changes in initial CLI.

## Repository workflow
Main is canonical for validated notes. Use feature branches for tools and experiments, document exact commands, sanitized results, assumptions, rollback notes and pass/fail criteria. Never commit proprietary APK/ELF/firmware, raw diagnostic archives, device identifiers or subscription secrets. Maintain chronological research log after each session.
