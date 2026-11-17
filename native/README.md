# nabu-vsim-probe

Source-only prototype. Uses Android platform HIDL APIs to query the existing VSIM service and call standard IBase ping and interfaceDescriptor. No vendor-specific methods, credential access, enrollment or settings changes.

Build in a compatible AOSP Android platform source environment by copying this directory under a suitable source path (e.g. vendor/nabu/vsim-probe), selecting an appropriate build target, then running m nabu-vsim-probe. Check Android version and platform library compatibility first. Standalone NDK does not expose the required platform HIDL headers. ARM32 chosen for initial compatibility; a binder service can in principle be reached across bitness.

Not yet compiled or tested. Device deployment and SELinux access require validation. Do not weaken SELinux to force access.
