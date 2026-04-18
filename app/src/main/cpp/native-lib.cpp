#include<jni.h> // JNI types and functions
#include<arm_neon.h> // ARM NEON intrinsics
#include<android/log.h> // Android logging
#define LOG_TAG "AcceleratedCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
extern "C" // Prevents C++ name mangling so JNI can find the function
JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_processPixelsSIMD(
        JNIEnv *env, // JNI environment pointer (required by JNI)
        jobject /* this */, // The NativeLib object (unused for static-like calls)
        jintArray pixelArray, // The IntArray from Kotlin
        jint length // Number of pixels
        )
        {
            // 1. Get a direct pointer to the Kotlin IntArray's memory
            // "isCopy" tells us if JNI made a copy (we don't need to check)
            jint *pixels = env-> GetIntArrayElements(pixelArray,nullptr);
            if(pixels == nullptr) return; // Out of memory
            // 2. Create a NEON vector filled with 0xFF (255) in every byte
            // This represents the value we XOR against to invert:
            // inverted = 255 - original is the same as inverted = original XOR 0xFF
            // (but we need to preserve alpha, so we'll mask it)
            uint8x16_t allOnes = vdupq_n_u8(0xFF);
            // 3. Create an alpha mask: 0xFF000000 repeated for 4 pixels
            // In ARGB_8888 layout (little-endian memory): bytes are [B, G, R, A]
            // So the alpha byte is at position 3, 7, 11, 15
            // Mask: 0x00 for B,G,R channels (invert them), 0xFF for A (keep it)
            uint8_t alpha_mask_bytes[16] =
                    {0xFF,0xFF,0xFF,0x00, // pixel 0: invert B,G,R; keep A
                     0xFF,0xFF,0xFF,0x00,// pixel 1
                     0xFF,0xFF,0xFF,0x00,// pixel 2
                     0xFF,0xFF,0xFF,0x00 // pixel 3
                     };

            uint8x16_t invertMask = vld1q_u8(alpha_mask_bytes);
            // 4. Process 4 pixels at a time (4 pixels × 4 bytes = 16 bytes = 128 bits)
            int i = 0;
            int simdEnd = length - (length % 4); // Round down to multiple of 4
            for(i = 0; i < simdEnd; i +=4) {
                // Load 4 pixels (16 bytes) from memory into a NEON register
                uint8x16_t src = vld1q_u8(reinterpret_cast<uint8_t*>(&pixels[i]));

                // XOR with 0xFF to invert all channels
                uint8x16_t inverted = veorq_u8(src, allOnes);
                // Restore original alpha: blend using the mask
                // Where mask bit is 1 → use inverted; where 0 → use original
                // Actually, we want: for RGB use inverted, for A use original
                // bsl = bit select: result = (inverted AND mask) OR (src AND NOT mask)
                uint8x16_t result = vbslq_u8(invertMask, inverted, src);

                // Store the 4 processed pixels back to memory
                vst1q_u8(reinterpret_cast<uint8_t*>(&pixels[i]), result);
            }

            // 5. Handle remaining pixels (if width*height is not divisible by 4)
            for(; i < length; i++){
                jint pixel = pixels[i];
                jint a = (pixel >> 24) & 0xFF;
                jint r = 255 - ((pixel >> 16) & 0xFF);
                jint g = 255 - ((pixel >> 8) & 0xFF);
                jint b = 255 - (pixel & 0xFF);
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }

            // 6. Release the array back to Kotlin (0 = copy changes back)
            env->ReleaseIntArrayElements(pixelArray, pixels,0);
        }

#include "native-filters.cpp"