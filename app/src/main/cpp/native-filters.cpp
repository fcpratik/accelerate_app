#include <jni.h>
#include <arm_neon.h>
#include <algorithm>
#include <cmath>

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_sepiaSimd(JNIEnv *env, jobject, jintArray pixelArray, jint length) {
    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    int simdEnd = length - (length % 4);
    for (int i = 0; i < simdEnd; i += 4) {
        uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[i]);

        uint32_t r_arr[4] = {p[2], p[6], p[10], p[14]};
        uint32_t g_arr[4] = {p[1], p[5], p[9], p[13]};
        uint32_t b_arr[4] = {p[0], p[4], p[8], p[12]};

        float32x4_t r_f = vcvtq_f32_u32(vld1q_u32(r_arr));
        float32x4_t g_f = vcvtq_f32_u32(vld1q_u32(g_arr));
        float32x4_t b_f = vcvtq_f32_u32(vld1q_u32(b_arr));

        float32x4_t const_r = vdupq_n_f32(0.299f);
        float32x4_t const_g = vdupq_n_f32(0.587f);
        float32x4_t const_b = vdupq_n_f32(0.114f);

        float32x4_t gray = vmulq_f32(r_f, const_r);
        gray = vmlaq_f32(gray, g_f, const_g);
        gray = vmlaq_f32(gray, b_f, const_b);

        float32x4_t new_r = vaddq_f32(vmulq_f32(gray, vdupq_n_f32(1.2f)), vdupq_n_f32(40.0f));
        float32x4_t new_g = vaddq_f32(vmulq_f32(gray, vdupq_n_f32(1.0f)), vdupq_n_f32(20.0f));
        float32x4_t new_b = vmulq_f32(gray, vdupq_n_f32(0.8f));

        uint32x4_t r_u = vcvtq_u32_f32(new_r);
        uint32x4_t g_u = vcvtq_u32_f32(new_g);
        uint32x4_t b_u = vcvtq_u32_f32(new_b);

        uint32x4_t max_val = vdupq_n_u32(255);
        r_u = vminq_u32(r_u, max_val);
        g_u = vminq_u32(g_u, max_val);
        b_u = vminq_u32(b_u, max_val);

        uint32_t r_out[4], g_out[4], b_out[4];
        vst1q_u32(r_out, r_u);
        vst1q_u32(g_out, g_u);
        vst1q_u32(b_out, b_u);

        p[2]=r_out[0]; p[6]=r_out[1]; p[10]=r_out[2]; p[14]=r_out[3];
        p[1]=g_out[0]; p[5]=g_out[1]; p[9]=g_out[2]; p[13]=g_out[3];
        p[0]=b_out[0]; p[4]=b_out[1]; p[8]=b_out[2]; p[12]=b_out[3];
    }

    for (int i = simdEnd; i < length; i++) {
        uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[i]);
        float r = p[2], g = p[1], b = p[0];
        float gray = 0.299f * r + 0.587f * g + 0.114f * b;
        p[2] = std::min(255.0f, gray * 1.2f + 40.0f);
        p[1] = std::min(255.0f, gray * 1.0f + 20.0f);
        p[0] = std::min(255.0f, gray * 0.8f);
    }

    env->ReleaseIntArrayElements(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_gaussianBlurSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    jint* tempPixels = new jint[width * height];
    memcpy(tempPixels, pixels, width * height * sizeof(jint));

    int kernel[5][5] = {
        {1, 4, 7, 4, 1},
        {4, 16, 26, 16, 4},
        {7, 26, 41, 26, 7},
        {4, 16, 26, 16, 4},
        {1, 4, 7, 4, 1}
    };
    int kernelSum = 273;
    int simdEnd = width - (width % 4);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < simdEnd; x += 4) {
            int32x4_t sumR = vdupq_n_s32(0);
            int32x4_t sumG = vdupq_n_s32(0);
            int32x4_t sumB = vdupq_n_s32(0);

            for (int ky = -2; ky <= 2; ky++) {
                for (int kx = -2; kx <= 2; kx++) {
                    int ny = std::max(0, std::min(height - 1, y + ky));
                    
                    int32_t r_arr[4], g_arr[4], b_arr[4];
                    for (int i = 0; i < 4; i++) {
                        int nx = std::max(0, std::min(width - 1, x + i + kx));
                        uint8_t* val = reinterpret_cast<uint8_t*>(&tempPixels[ny * width + nx]);
                        b_arr[i] = val[0];
                        g_arr[i] = val[1];
                        r_arr[i] = val[2];
                    }

                    int32x4_t r_vec = vld1q_s32(r_arr);
                    int32x4_t g_vec = vld1q_s32(g_arr);
                    int32x4_t b_vec = vld1q_s32(b_arr);
                    int32x4_t w_vec = vdupq_n_s32(kernel[ky + 2][kx + 2]);

                    sumR = vmlaq_s32(sumR, r_vec, w_vec);
                    sumG = vmlaq_s32(sumG, g_vec, w_vec);
                    sumB = vmlaq_s32(sumB, b_vec, w_vec);
                }
            }

            int32_t r_out[4], g_out[4], b_out[4];
            vst1q_s32(r_out, sumR);
            vst1q_s32(g_out, sumG);
            vst1q_s32(b_out, sumB);

            for (int i = 0; i < 4; i++) {
                uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x + i]);
                p[2] = r_out[i] / kernelSum;
                p[1] = g_out[i] / kernelSum;
                p[0] = b_out[i] / kernelSum;
            }
        }

        for (int x = simdEnd; x < width; x++) {
            int sumR = 0, sumG = 0, sumB = 0;
            for (int ky = -2; ky <= 2; ky++) {
                for (int kx = -2; kx <= 2; kx++) {
                    int nx = std::max(0, std::min(width - 1, x + kx));
                    int ny = std::max(0, std::min(height - 1, y + ky));
                    uint8_t* val = reinterpret_cast<uint8_t*>(&tempPixels[ny * width + nx]);
                    int w = kernel[ky + 2][kx + 2];
                    sumB += val[0] * w;
                    sumG += val[1] * w;
                    sumR += val[2] * w;
                }
            }
            uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x]);
            p[2] = sumR / kernelSum;
            p[1] = sumG / kernelSum;
            p[0] = sumB / kernelSum;
        }
    }

    delete[] tempPixels;
    env->ReleaseIntArrayElements(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_sobelEdgeSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    jint* tempPixels = new jint[width * height];
    memcpy(tempPixels, pixels, width * height * sizeof(jint));

    int gxKernel[3][3] = { {-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1} };
    int gyKernel[3][3] = { {-1, -2, -1}, {0, 0, 0}, {1, 2, 1} };
    int simdEnd = width - (width % 4);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < simdEnd; x += 4) {
            int32x4_t sumX = vdupq_n_s32(0);
            int32x4_t sumY = vdupq_n_s32(0);

            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int ny = std::max(0, std::min(height - 1, y + ky));
                    
                    int32_t gray_arr[4];
                    for (int i = 0; i < 4; i++) {
                        int nx = std::max(0, std::min(width - 1, x + i + kx));
                        uint8_t* val = reinterpret_cast<uint8_t*>(&tempPixels[ny * width + nx]);
                        gray_arr[i] = (0.299f * val[2] + 0.587f * val[1] + 0.114f * val[0]);
                    }

                    int32x4_t gray_vec = vld1q_s32(gray_arr);
                    int32x4_t wx_vec = vdupq_n_s32(gxKernel[ky + 1][kx + 1]);
                    int32x4_t wy_vec = vdupq_n_s32(gyKernel[ky + 1][kx + 1]);

                    sumX = vmlaq_s32(sumX, gray_vec, wx_vec);
                    sumY = vmlaq_s32(sumY, gray_vec, wy_vec);
                }
            }
            
            float32x4_t sumX_f = vcvtq_f32_s32(sumX);
            float32x4_t sumY_f = vcvtq_f32_s32(sumY);
            float32x4_t magSq = vaddq_f32(vmulq_f32(sumX_f, sumX_f), vmulq_f32(sumY_f, sumY_f));
            
            float32x4_t invSqrt = vrsqrteq_f32(magSq);
            invSqrt = vmulq_f32(vrsqrtsq_f32(vmulq_f32(magSq, invSqrt), invSqrt), invSqrt);
            float32x4_t mag = vmulq_f32(magSq, invSqrt);
            
            uint32x4_t mag_u32 = vminq_u32(vcvtq_u32_f32(mag), vdupq_n_u32(255));
            uint32_t mag_arr[4];
            vst1q_u32(mag_arr, mag_u32);

            for (int i = 0; i < 4; i++) {
                uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x + i]);
                p[2] = mag_arr[i]; p[1] = mag_arr[i]; p[0] = mag_arr[i];
            }
        }
        
        for (int x = simdEnd; x < width; x++) {
            int sumX = 0, sumY = 0;
            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int nx = std::max(0, std::min(width - 1, x + kx));
                    int ny = std::max(0, std::min(height - 1, y + ky));
                    uint8_t* val = reinterpret_cast<uint8_t*>(&tempPixels[ny * width + nx]);
                    int gray = 0.299f * val[2] + 0.587f * val[1] + 0.114f * val[0];
                    sumX += gray * gxKernel[ky + 1][kx + 1];
                    sumY += gray * gyKernel[ky + 1][kx + 1];
                }
            }
            int mag = std::min(255, (int)std::sqrt(sumX*sumX + sumY*sumY));
            uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x]);
            p[2] = mag; p[1] = mag; p[0] = mag;
        }
    }

    delete[] tempPixels;
    env->ReleaseIntArrayElements(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_embossSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    jint* tempPixels = new jint[width * height];
    memcpy(tempPixels, pixels, width * height * sizeof(jint));

    int kernel[3][3] = { {-2, -1, 0}, {-1, 1, 1}, {0, 1, 2} };
    int simdEnd = width - (width % 4);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < simdEnd; x += 4) {
            int32x4_t sumR = vdupq_n_s32(0);
            int32x4_t sumG = vdupq_n_s32(0);
            int32x4_t sumB = vdupq_n_s32(0);

            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int ny = std::max(0, std::min(height - 1, y + ky));
                    
                    int32_t r_arr[4], g_arr[4], b_arr[4];
                    for (int i = 0; i < 4; i++) {
                        int nx = std::max(0, std::min(width - 1, x + i + kx));
                        uint8_t* val = reinterpret_cast<uint8_t*>(&tempPixels[ny * width + nx]);
                        b_arr[i] = val[0]; g_arr[i] = val[1]; r_arr[i] = val[2];
                    }

                    int32x4_t r_vec = vld1q_s32(r_arr);
                    int32x4_t g_vec = vld1q_s32(g_arr);
                    int32x4_t b_vec = vld1q_s32(b_arr);
                    int32x4_t w_vec = vdupq_n_s32(kernel[ky + 1][kx + 1]);

                    sumR = vmlaq_s32(sumR, r_vec, w_vec);
                    sumG = vmlaq_s32(sumG, g_vec, w_vec);
                    sumB = vmlaq_s32(sumB, b_vec, w_vec);
                }
            }

            int32_t r_out[4], g_out[4], b_out[4];
            vst1q_s32(r_out, sumR);
            vst1q_s32(g_out, sumG);
            vst1q_s32(b_out, sumB);

            for (int i = 0; i < 4; i++) {
                uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x + i]);
                p[2] = std::max(0, std::min(255, r_out[i] + 128));
                p[1] = std::max(0, std::min(255, g_out[i] + 128));
                p[0] = std::max(0, std::min(255, b_out[i] + 128));
            }
        }
        
        for (int x = simdEnd; x < width; x++) {
            int sumR = 0, sumG = 0, sumB = 0;
            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int nx = std::max(0, std::min(width - 1, x + kx));
                    int ny = std::max(0, std::min(height - 1, y + ky));
                    uint8_t* val = reinterpret_cast<uint8_t*>(&tempPixels[ny * width + nx]);
                    int w = kernel[ky + 1][kx + 1];
                    sumB += val[0] * w; sumG += val[1] * w; sumR += val[2] * w;
                }
            }
            uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x]);
            p[2] = std::max(0, std::min(255, sumR + 128));
            p[1] = std::max(0, std::min(255, sumG + 128));
            p[0] = std::max(0, std::min(255, sumB + 128));
        }
    }

    delete[] tempPixels;
    env->ReleaseIntArrayElements(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_vignetteSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    float centerX = width / 2.0f;
    float centerY = height / 2.0f;
    float maxDist = std::sqrt(centerX * centerX + centerY * centerY);
    float maxDistInv = 1.0f / maxDist;
    int simdEnd = width - (width % 4);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < simdEnd; x += 4) {
            float dx[4] = { x - centerX, x + 1 - centerX, x + 2 - centerX, x + 3 - centerX };
            float dy[4] = { y - centerY, y - centerY, y - centerY, y - centerY };
            
            float32x4_t dx_vec = vld1q_f32(dx);
            float32x4_t dy_vec = vld1q_f32(dy);
            float32x4_t distSq = vaddq_f32(vmulq_f32(dx_vec, dx_vec), vmulq_f32(dy_vec, dy_vec));
            
            float32x4_t invSqrt = vrsqrteq_f32(distSq);
            invSqrt = vmulq_f32(vrsqrtsq_f32(vmulq_f32(distSq, invSqrt), invSqrt), invSqrt);
            float32x4_t dist = vmulq_f32(distSq, invSqrt);
            
            float32x4_t maxDistInv_vec = vdupq_n_f32(maxDistInv);
            float32x4_t factor = vsubq_f32(vdupq_n_f32(1.0f), vmulq_f32(dist, maxDistInv_vec));
            
            factor = vmaxq_f32(vdupq_n_f32(0.0f), vminq_f32(vdupq_n_f32(1.0f), factor));

            float factor_arr[4];
            vst1q_f32(factor_arr, factor);

            for (int i = 0; i < 4; i++) {
                uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x + i]);
                p[2] = p[2] * factor_arr[i];
                p[1] = p[1] * factor_arr[i];
                p[0] = p[0] * factor_arr[i];
            }
        }
        
        for (int x = simdEnd; x < width; x++) {
            float dx = x - centerX;
            float dy = y - centerY;
            float dist = std::sqrt(dx*dx + dy*dy);
            float factor = std::max(0.0f, std::min(1.0f, 1.0f - dist * maxDistInv));
            uint8_t* p = reinterpret_cast<uint8_t*>(&pixels[y * width + x]);
            p[2] = p[2] * factor;
            p[1] = p[1] * factor;
            p[0] = p[0] * factor;
        }
    }

    env->ReleaseIntArrayElements(pixelArray, pixels, 0);
}
