// Sonora Android adapter. The clock identity is always explicitly supplied.
#pragma once
#include <inttypes.h>
#include <stdint.h>
#include <android/log.h>
#define LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO,"SonoraPTP",__VA_ARGS__)
#define LOG_WARN(...) __android_log_print(ANDROID_LOG_WARN,"SonoraPTP",__VA_ARGS__)
#define LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR,"SonoraPTP",__VA_ARGS__)
#define LOG_DEBUG(...) do {} while(0)
// The upstream default-MAC path is never used by Sonora. Fail if that contract changes.
static inline void get_mac(uint8_t *mac){ (void)mac; abort(); }
