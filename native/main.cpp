#include <hidl/ServiceManagement.h>
#include <android/hidl/manager/1.0/IServiceManager.h>
#include <android/hidl/base/1.0/IBase.h>
#include <cstdio>

int main() {
    using android::sp;
    using android::hidl::base::V1_0::IBase;
    constexpr auto descriptor = "vendor.xiaomi.hardware.vsimapp@1.0::IVSIMService";
    auto manager = android::hardware::defaultServiceManager();
    if (manager == nullptr) {
        std::fprintf(stderr, "Service manager unavailable\n");
        return 1;
    }
    auto found = manager->get(descriptor, "default");
    if (!found.isOk()) {
        std::fprintf(stderr, "Lookup transport failed: %s\n", found.description().c_str());
        return 2;
    }
    sp<IBase> service = found;
    if (service == nullptr) {
        std::fprintf(stderr, "Service unavailable or inaccessible\n");
        return 3;
    }
    auto ping = service->ping();
    if (!ping.isOk()) {
        std::fprintf(stderr, "Ping failed: %s\n", ping.description().c_str());
        return 4;
    }
    std::puts("IBase ping OK");
    bool matched = false;
    auto result = service->interfaceDescriptor([&](const android::hardware::hidl_string& value) {
        std::printf("Descriptor: %s\n", value.c_str());
        matched = value == descriptor;
    });
    if (!result.isOk()) {
        std::fprintf(stderr, "Descriptor query failed: %s\n", result.description().c_str());
        return 5;
    }
    return matched ? 0 : 6;
}
