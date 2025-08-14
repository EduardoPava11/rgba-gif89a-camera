#!/bin/bash
# Extract contract version from library using adb

adb -s R5CX62YBM4H shell << 'ENDSCRIPT'
# Create a simple test program to check the contract version
cat > /data/local/tmp/test_uniffi.c << 'EOC'
#include <dlfcn.h>
#include <stdio.h>

int main() {
    void* lib = dlopen("/data/data/com.rgbagif.debug/lib/libuniffi_m3gif.so", RTLD_NOW);
    if (!lib) {
        printf("Failed to load library: %s\n", dlerror());
        return 1;
    }
    
    unsigned int (*get_version)() = dlsym(lib, "ffi_m3gif_uniffi_contract_version");
    if (!get_version) {
        printf("Failed to find function: %s\n", dlerror());
        return 1;
    }
    
    printf("Contract version: %u\n", get_version());
    dlclose(lib);
    return 0;
}
EOC
ENDSCRIPT

echo "Created test program"
