#include <jni.h>
#include <arm_neon.h>
#include <algorithm>
#include <cmath>
#include <vector>
#include <cstring>



extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_sepiaSimd(JNIEnv *env, jobject, jintArray pixelArray, jint length) {
    // 1. ZERO-COPY ACCESS: Blocks GC and passes direct memory pointer
    jint *pixels = (jint*) env->GetPrimitiveArrayCritical(pixelArray, nullptr);
    if (!pixels) return;

    uint8_t* bytePixels = reinterpret_cast<uint8_t*>(pixels);

    int simdEnd = length - (length % 16);

    // Load constants into registers once, outside the loop
    uint8x8_t w_r = vdup_n_u8(77);
    uint8x8_t w_g = vdup_n_u8(150);
    uint8x8_t w_b = vdup_n_u8(29);
    uint16x8_t v_40  = vdupq_n_u16(40);
    uint16x8_t v_51  = vdupq_n_u16(51);
    uint8x16_t v_20  = vdupq_n_u8(20);
    uint16x8_t v_205 = vdupq_n_u16(205);

    for (int i = 0; i < simdEnd; i += 16) {
        // 2. PREFETCHING: Tell the CPU to fetch memory 128 bytes ahead.
        // '1' means we intend to write to it, '0' means low temporal locality.
        __builtin_prefetch(bytePixels + (i * 4) + 128, 1, 0);

        uint8x16x4_t argb = vld4q_u8(bytePixels + (i * 4));

        // --- Grayscale ---
        uint16x8_t gray1 = vmull_u8(vget_low_u8(argb.val[2]), w_r);
        gray1 = vmlal_u8(gray1, vget_low_u8(argb.val[1]), w_g);
        gray1 = vmlal_u8(gray1, vget_low_u8(argb.val[0]), w_b);
        uint8x8_t g8_low = vshrn_n_u16(gray1, 8);

        uint16x8_t gray2 = vmull_u8(vget_high_u8(argb.val[2]), w_r);
        gray2 = vmlal_u8(gray2, vget_high_u8(argb.val[1]), w_g);
        gray2 = vmlal_u8(gray2, vget_high_u8(argb.val[0]), w_b);
        uint8x8_t g8_high = vshrn_n_u16(gray2, 8);

        uint8x16_t gray16 = vcombine_u8(g8_low, g8_high);

        // --- Red Channel ---
        uint16x8_t g16_low = vmovl_u8(g8_low);
        uint16x8_t r16_low = vaddq_u16(g16_low, v_40);
        r16_low = vaddq_u16(r16_low, vshrq_n_u16(vmulq_u16(g16_low, v_51), 8));

        uint16x8_t g16_high = vmovl_u8(g8_high);
        uint16x8_t r16_high = vaddq_u16(g16_high, v_40);
        r16_high = vaddq_u16(r16_high, vshrq_n_u16(vmulq_u16(g16_high, v_51), 8));

        argb.val[2] = vcombine_u8(vqmovn_u16(r16_low), vqmovn_u16(r16_high));

        // --- Green Channel ---
        argb.val[1] = vqaddq_u8(gray16, v_20);

        // --- Blue Channel ---
        uint8x8_t b8_low = vshrn_n_u16(vmulq_u16(g16_low, v_205), 8);
        uint8x8_t b8_high = vshrn_n_u16(vmulq_u16(g16_high, v_205), 8);
        argb.val[0] = vcombine_u8(b8_low, b8_high);

        vst4q_u8(bytePixels + (i * 4), argb);
    }

    // Tail loop
    for (int i = simdEnd; i < length; i++) {
        uint8_t* p = bytePixels + (i * 4);
        float r = p[2], g = p[1], b = p[0];
        float gray = 0.299f * r + 0.587f * g + 0.114f * b;
        p[2] = std::min(255.0f, gray * 1.2f + 40.0f);
        p[1] = std::min(255.0f, gray * 1.0f + 20.0f);
        p[0] = std::min(255.0f, gray * 0.8f);
    }

    // 3. RELEASE CRITICAL: Must be called to unpause the Java Garbage Collector
    env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_gaussianBlurSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
    jint *pixels = (jint*) env->GetPrimitiveArrayCritical(pixelArray, nullptr);
    if (!pixels) return;

    int totalPixels = width * height;
    static std::vector<jint> tempPixelsVec;
    tempPixelsVec.resize(totalPixels);
    uint8_t* bytePixels = reinterpret_cast<uint8_t*>(pixels);
    uint8_t* tempBytePixels = reinterpret_cast<uint8_t*>(tempPixelsVec.data());

    // PASS 1: Horizontal Blur (Processing 8 pixels at a time)
    for (int y = 0; y < height; y++) {
        int x = 2;
        int limit = width - 2;
        int simdEnd = limit - ((limit - 2) % 8);

        for (; x < simdEnd; x += 8) {
            // Overlapping loads: Read 8 pixels (32 bytes) for each position in the kernel
            uint8x8x4_t p_m2 = vld4_u8(bytePixels + (y * width + x - 2) * 4);
            uint8x8x4_t p_m1 = vld4_u8(bytePixels + (y * width + x - 1) * 4);
            uint8x8x4_t p_0  = vld4_u8(bytePixels + (y * width + x) * 4);
            uint8x8x4_t p_p1 = vld4_u8(bytePixels + (y * width + x + 1) * 4);
            uint8x8x4_t p_p2 = vld4_u8(bytePixels + (y * width + x + 2) * 4);

            uint8x8x4_t out_argb;

            // Apply kernel {1, 4, 6, 4, 1} using purely Bitwise Shifts to all 3 color channels
            for (int c = 0; c < 3; c++) {
                // p[-2] * 1
                uint16x8_t sum = vmovl_u8(p_m2.val[c]);
                // p[-1] * 4 (Shift left 2)
                sum = vaddq_u16(sum, vshlq_n_u16(vmovl_u8(p_m1.val[c]), 2));
                // p[0] * 6  (Shift left 2 + Shift left 1)
                uint16x8_t center = vmovl_u8(p_0.val[c]);
                sum = vaddq_u16(sum, vaddq_u16(vshlq_n_u16(center, 2), vshlq_n_u16(center, 1)));
                // p[1] * 4
                sum = vaddq_u16(sum, vshlq_n_u16(vmovl_u8(p_p1.val[c]), 2));
                // p[2] * 1
                sum = vaddq_u16(sum, vmovl_u8(p_p2.val[c]));

                // Divide by 16 (Shift right 4) and narrow back to 8-bit
                out_argb.val[c] = vshrn_n_u16(sum, 4);
            }
            out_argb.val[3] = vdup_n_u8(255); // Keep Alpha at 255

            // Store the 8 fully blurred pixels back into the temporary buffer
            vst4_u8(tempBytePixels + (y * width + x) * 4, out_argb);
        }

        // Tail loop for horizontal
        for (; x < limit; x++) {
            int sumR = 0, sumG = 0, sumB = 0;
            int kernel[5] = {1, 4, 6, 4, 1};
            for (int kx = -2; kx <= 2; kx++) {
                uint8_t* val = bytePixels + ((y * width + x + kx) * 4);
                sumB += val[0] * kernel[kx + 2];
                sumG += val[1] * kernel[kx + 2];
                sumR += val[2] * kernel[kx + 2];
            }
            uint8_t* pOut = tempBytePixels + ((y * width + x) * 4);
            pOut[0] = sumB >> 4; pOut[1] = sumG >> 4; pOut[2] = sumR >> 4; pOut[3] = 255;
        }
    }

    // PASS 2: Vertical Blur
    for (int y = 2; y < height - 2; y++) {
        int x = 0;
        int simdEnd = width - (width % 8);

        for (; x < simdEnd; x += 8) {
            // Overlapping loads vertically (reading pointers spaced exactly by row width)
            uint8x8x4_t p_m2 = vld4_u8(tempBytePixels + ((y - 2) * width + x) * 4);
            uint8x8x4_t p_m1 = vld4_u8(tempBytePixels + ((y - 1) * width + x) * 4);
            uint8x8x4_t p_0  = vld4_u8(tempBytePixels + (y * width + x) * 4);
            uint8x8x4_t p_p1 = vld4_u8(tempBytePixels + ((y + 1) * width + x) * 4);
            uint8x8x4_t p_p2 = vld4_u8(tempBytePixels + ((y + 2) * width + x) * 4);

            uint8x8x4_t out_argb;

            for (int c = 0; c < 3; c++) {
                uint16x8_t sum = vmovl_u8(p_m2.val[c]);
                sum = vaddq_u16(sum, vshlq_n_u16(vmovl_u8(p_m1.val[c]), 2));
                uint16x8_t center = vmovl_u8(p_0.val[c]);
                sum = vaddq_u16(sum, vaddq_u16(vshlq_n_u16(center, 2), vshlq_n_u16(center, 1)));
                sum = vaddq_u16(sum, vshlq_n_u16(vmovl_u8(p_p1.val[c]), 2));
                sum = vaddq_u16(sum, vmovl_u8(p_p2.val[c]));
                out_argb.val[c] = vshrn_n_u16(sum, 4);
            }
            out_argb.val[3] = vdup_n_u8(255);

            vst4_u8(bytePixels + (y * width + x) * 4, out_argb);
        }

        // Tail loop for vertical
        for (; x < width; x++) {
            int sumR = 0, sumG = 0, sumB = 0;
            int kernel[5] = {1, 4, 6, 4, 1};
            for (int ky = -2; ky <= 2; ky++) {
                uint8_t* val = tempBytePixels + ((y + ky) * width + x) * 4;
                sumB += val[0] * kernel[ky + 2];
                sumG += val[1] * kernel[ky + 2];
                sumR += val[2] * kernel[ky + 2];
            }
            uint8_t* pOut = bytePixels + ((y * width + x) * 4);
            pOut[0] = sumB >> 4; pOut[1] = sumG >> 4; pOut[2] = sumR >> 4;
        }
    }

    env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_sobelEdgeSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
    jint *pixels = (jint*) env->GetPrimitiveArrayCritical(pixelArray, nullptr);
    if (!pixels) return;

    int totalPixels = width * height;
    static std::vector<uint8_t> grayPixelsVec;
    grayPixelsVec.resize(totalPixels);
    uint8_t* grayPixels = grayPixelsVec.data();
    uint8_t* bytePixels = reinterpret_cast<uint8_t*>(pixels);

    // Create our weight vectors once outside the loop
    uint8x8_t w_r = vdup_n_u8(77);
    uint8x8_t w_g = vdup_n_u8(150);
    uint8x8_t w_b = vdup_n_u8(29);

    // PASS 1: Structural Loads and Fixed-Point Math
    int simdEnd16 = totalPixels - (totalPixels % 16);
    for (int i = 0; i < simdEnd16; i += 16) {
        // vld4q_u8 loads 64 bytes and separates B, G, R, and A
        uint8x16x4_t argb = vld4q_u8(bytePixels + (i * 4));

        // Use standard vector-by-vector multiplication
        // vmull_u8 multiplies two 8-bit vectors and outputs a 16-bit vector
        uint16x8_t gray1 = vmull_u8(vget_low_u8(argb.val[2]), w_r); // R * 77
        gray1 = vmlal_u8(gray1, vget_low_u8(argb.val[1]), w_g);     // + G * 150
        gray1 = vmlal_u8(gray1, vget_low_u8(argb.val[0]), w_b);     // + B * 29

        uint16x8_t gray2 = vmull_u8(vget_high_u8(argb.val[2]), w_r);
        gray2 = vmlal_u8(gray2, vget_high_u8(argb.val[1]), w_g);
        gray2 = vmlal_u8(gray2, vget_high_u8(argb.val[0]), w_b);

        // vshrn_n_u16 shifts right by 8 (divides by 256) and narrows back to 8-bit
        uint8x8_t res1 = vshrn_n_u16(gray1, 8);
        uint8x8_t res2 = vshrn_n_u16(gray2, 8);

        // Combine the two 8-pixel halves and store
        uint8x16_t finalGray = vcombine_u8(res1, res2);
        vst1q_u8(grayPixels + i, finalGray);
    }

    // Tail loop for Pass 1
    for (int i = simdEnd16; i < totalPixels; i++) {
        uint8_t* p = bytePixels + (i * 4);
        grayPixels[i] = (uint8_t)((p[2]*77 + p[1]*150 + p[0]*29) >> 8);
    }

    int gxKernel[3][3] = { {-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1} };
    int gyKernel[3][3] = { {-1, -2, -1}, {0, 0, 0}, {1, 2, 1} };

    // PASS 2: Vectorized Sobel Convolution
    for (int y = 1; y < height - 1; y++) {
        int x = 1;
        int limit = width - 1;
        int simdEnd = limit - ((limit - 1) % 8);

        for (; x < simdEnd; x += 8) {
            // Overlapping cache loads for top, mid, and bottom rows (1 byte per pixel since it's grayscale)
            uint16x8_t tl = vmovl_u8(vld1_u8(grayPixels + (y - 1) * width + x - 1));
            uint16x8_t tc = vmovl_u8(vld1_u8(grayPixels + (y - 1) * width + x));
            uint16x8_t tr = vmovl_u8(vld1_u8(grayPixels + (y - 1) * width + x + 1));

            uint16x8_t ml = vmovl_u8(vld1_u8(grayPixels + y * width + x - 1));
            uint16x8_t mr = vmovl_u8(vld1_u8(grayPixels + y * width + x + 1));

            uint16x8_t bl = vmovl_u8(vld1_u8(grayPixels + (y + 1) * width + x - 1));
            uint16x8_t bc = vmovl_u8(vld1_u8(grayPixels + (y + 1) * width + x));
            uint16x8_t br = vmovl_u8(vld1_u8(grayPixels + (y + 1) * width + x + 1));

            // Compute Gx columns: (Right Col) vs (Left Col). Multipliers of 2 become shift-lefts.
            uint16x8_t col_r = vaddq_u16(tr, br);
            col_r = vaddq_u16(col_r, vshlq_n_u16(mr, 1));

            uint16x8_t col_l = vaddq_u16(tl, bl);
            col_l = vaddq_u16(col_l, vshlq_n_u16(ml, 1));

            // Compute Gy rows: (Bottom Row) vs (Top Row)
            uint16x8_t row_b = vaddq_u16(bl, br);
            row_b = vaddq_u16(row_b, vshlq_n_u16(bc, 1));

            uint16x8_t row_t = vaddq_u16(tl, tr);
            row_t = vaddq_u16(row_t, vshlq_n_u16(tc, 1));

            // vabdq_u16 automatically calculates the Absolute Difference between the two vectors!
            uint16x8_t abs_gx = vabdq_u16(col_r, col_l);
            uint16x8_t abs_gy = vabdq_u16(row_b, row_t);

            // Magnitude = |Gx| + |Gy|. Then clamp to 8-bit using vqmovn.
            uint8x8_t mag8 = vqmovn_u16(vaddq_u16(abs_gx, abs_gy));

            // Write back to all 3 color channels
            uint8x8x4_t out_argb;
            out_argb.val[0] = mag8;
            out_argb.val[1] = mag8;
            out_argb.val[2] = mag8;
            out_argb.val[3] = vdup_n_u8(255);

            vst4_u8(bytePixels + ((y * width + x) * 4), out_argb);
        }

        // Tail loop for Pass 2
        int gxKernel[3][3] = { {-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1} };
        int gyKernel[3][3] = { {-1, -2, -1}, {0, 0, 0}, {1, 2, 1} };
        for (; x < limit; x++) {
            int sumX = 0, sumY = 0;
            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int pixelVal = grayPixels[(y + ky) * width + (x + kx)];
                    sumX += pixelVal * gxKernel[ky + 1][kx + 1];
                    sumY += pixelVal * gyKernel[ky + 1][kx + 1];
                }
            }
            int mag = std::min(255, std::abs(sumX) + std::abs(sumY));
            uint8_t* p = bytePixels + ((y * width + x) * 4);
            p[2] = mag; p[1] = mag; p[0] = mag;
        }
    }

    env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_embossSimd(
        JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {

    jint *pixels = (jint*) env->GetPrimitiveArrayCritical(pixelArray, nullptr);
    if (!pixels) return;

    int total = width * height;
    uint8_t* bytePixels = reinterpret_cast<uint8_t*>(pixels);

    static std::vector<uint8_t> srcRVec, srcGVec, srcBVec;
    srcRVec.resize(total); srcGVec.resize(total); srcBVec.resize(total);
    uint8_t* srcR = srcRVec.data();
    uint8_t* srcG = srcGVec.data();
    uint8_t* srcB = srcBVec.data();

    int idx = 0;
    for (; idx + 7 < total; idx += 8) {
        // vld4_u8 loads 8 pixels and auto-splits into channels
        uint8x8x4_t px = vld4_u8(bytePixels + idx * 4);
        vst1_u8(srcR + idx, px.val[0]);  // 8 R values → contiguous array
        vst1_u8(srcG + idx, px.val[1]);  // 8 G values → contiguous array
        vst1_u8(srcB + idx, px.val[2]);  // 8 B values → contiguous array
    }
    // Scalar remainder for extraction
    for (; idx < total; idx++) {
        uint8_t* p = bytePixels + idx * 4;
        srcR[idx] = p[0];
        srcG[idx] = p[1];
        srcB[idx] = p[2];
    }



    for (int y = 1; y < height - 1; y++) {

        int x = 1;

        for (; x + 8 < width - 1; x += 8) {

            uint8x8_t tl_r = vld1_u8(srcR + (y - 1) * width + x - 1);
            uint8x8_t tc_r = vld1_u8(srcR + (y - 1) * width + x);
            uint8x8_t ml_r = vld1_u8(srcR + y * width + x - 1);
            uint8x8_t mc_r = vld1_u8(srcR + y * width + x);
            uint8x8_t mr_r = vld1_u8(srcR + y * width + x + 1);
            uint8x8_t bc_r = vld1_u8(srcR + (y + 1) * width + x);
            uint8x8_t br_r = vld1_u8(srcR + (y + 1) * width + x + 1);


            int16x8_t sum_r = vdupq_n_s16(128);  // start with bias

            // Positive terms: +MC +MR +BC +2*BR
            sum_r = vaddq_s16(sum_r, vreinterpretq_s16_u16(vmovl_u8(mc_r)));
            sum_r = vaddq_s16(sum_r, vreinterpretq_s16_u16(vmovl_u8(mr_r)));
            sum_r = vaddq_s16(sum_r, vreinterpretq_s16_u16(vmovl_u8(bc_r)));
            sum_r = vaddq_s16(sum_r, vshlq_n_s16(vreinterpretq_s16_u16(vmovl_u8(br_r)), 1));  // 2*BR

            // Negative terms: -2*TL -TC -ML
            sum_r = vsubq_s16(sum_r, vshlq_n_s16(vreinterpretq_s16_u16(vmovl_u8(tl_r)), 1));  // -2*TL
            sum_r = vsubq_s16(sum_r, vreinterpretq_s16_u16(vmovl_u8(tc_r)));  // -TC
            sum_r = vsubq_s16(sum_r, vreinterpretq_s16_u16(vmovl_u8(ml_r)));  // -ML

            // Clamp to [0, 255]
            sum_r = vmaxq_s16(sum_r, vdupq_n_s16(0));
            sum_r = vminq_s16(sum_r, vdupq_n_s16(255));
            uint8x8_t out_r = vmovn_u16(vreinterpretq_u16_s16(sum_r));

            // ── Process G channel (same math, different source) ──
            uint8x8_t tl_g = vld1_u8(srcG + (y - 1) * width + x - 1);
            uint8x8_t tc_g = vld1_u8(srcG + (y - 1) * width + x);
            uint8x8_t ml_g = vld1_u8(srcG + y * width + x - 1);
            uint8x8_t mc_g = vld1_u8(srcG + y * width + x);
            uint8x8_t mr_g = vld1_u8(srcG + y * width + x + 1);
            uint8x8_t bc_g = vld1_u8(srcG + (y + 1) * width + x);
            uint8x8_t br_g = vld1_u8(srcG + (y + 1) * width + x + 1);

            int16x8_t sum_g = vdupq_n_s16(128);
            sum_g = vaddq_s16(sum_g, vreinterpretq_s16_u16(vmovl_u8(mc_g)));
            sum_g = vaddq_s16(sum_g, vreinterpretq_s16_u16(vmovl_u8(mr_g)));
            sum_g = vaddq_s16(sum_g, vreinterpretq_s16_u16(vmovl_u8(bc_g)));
            sum_g = vaddq_s16(sum_g, vshlq_n_s16(vreinterpretq_s16_u16(vmovl_u8(br_g)), 1));
            sum_g = vsubq_s16(sum_g, vshlq_n_s16(vreinterpretq_s16_u16(vmovl_u8(tl_g)), 1));
            sum_g = vsubq_s16(sum_g, vreinterpretq_s16_u16(vmovl_u8(tc_g)));
            sum_g = vsubq_s16(sum_g, vreinterpretq_s16_u16(vmovl_u8(ml_g)));
            sum_g = vmaxq_s16(sum_g, vdupq_n_s16(0));
            sum_g = vminq_s16(sum_g, vdupq_n_s16(255));
            uint8x8_t out_g = vmovn_u16(vreinterpretq_u16_s16(sum_g));

            // ── Process B channel ──
            uint8x8_t tl_b = vld1_u8(srcB + (y - 1) * width + x - 1);
            uint8x8_t tc_b = vld1_u8(srcB + (y - 1) * width + x);
            uint8x8_t ml_b = vld1_u8(srcB + y * width + x - 1);
            uint8x8_t mc_b = vld1_u8(srcB + y * width + x);
            uint8x8_t mr_b = vld1_u8(srcB + y * width + x + 1);
            uint8x8_t bc_b = vld1_u8(srcB + (y + 1) * width + x);
            uint8x8_t br_b = vld1_u8(srcB + (y + 1) * width + x + 1);

            int16x8_t sum_b = vdupq_n_s16(128);
            sum_b = vaddq_s16(sum_b, vreinterpretq_s16_u16(vmovl_u8(mc_b)));
            sum_b = vaddq_s16(sum_b, vreinterpretq_s16_u16(vmovl_u8(mr_b)));
            sum_b = vaddq_s16(sum_b, vreinterpretq_s16_u16(vmovl_u8(bc_b)));
            sum_b = vaddq_s16(sum_b, vshlq_n_s16(vreinterpretq_s16_u16(vmovl_u8(br_b)), 1));
            sum_b = vsubq_s16(sum_b, vshlq_n_s16(vreinterpretq_s16_u16(vmovl_u8(tl_b)), 1));
            sum_b = vsubq_s16(sum_b, vreinterpretq_s16_u16(vmovl_u8(tc_b)));
            sum_b = vsubq_s16(sum_b, vreinterpretq_s16_u16(vmovl_u8(ml_b)));
            sum_b = vmaxq_s16(sum_b, vdupq_n_s16(0));
            sum_b = vminq_s16(sum_b, vdupq_n_s16(255));
            uint8x8_t out_b = vmovn_u16(vreinterpretq_u16_s16(sum_b));

            // ── Store 8 embossed pixels ──
            uint8x8x4_t result;
            result.val[0] = out_r;             // channel 0
            result.val[1] = out_g;             // channel 1
            result.val[2] = out_b;             // channel 2
            result.val[3] = vdup_n_u8(0xFF);   // alpha = 255
            vst4_u8(bytePixels + (y * width + x) * 4, result);
        }

        // ═══ Scalar remainder for this row ═══
        for (; x < width - 1; x++) {
            int pos = y * width + x;
            int sumR = 0, sumG = 0, sumB = 0;

            // -2*TL
            sumR += -2 * srcR[(y-1)*width + x-1];
            sumG += -2 * srcG[(y-1)*width + x-1];
            sumB += -2 * srcB[(y-1)*width + x-1];
            // -1*TC
            sumR += -1 * srcR[(y-1)*width + x];
            sumG += -1 * srcG[(y-1)*width + x];
            sumB += -1 * srcB[(y-1)*width + x];
            // -1*ML
            sumR += -1 * srcR[y*width + x-1];
            sumG += -1 * srcG[y*width + x-1];
            sumB += -1 * srcB[y*width + x-1];
            // +1*MC
            sumR += srcR[y*width + x];
            sumG += srcG[y*width + x];
            sumB += srcB[y*width + x];
            // +1*MR
            sumR += srcR[y*width + x+1];
            sumG += srcG[y*width + x+1];
            sumB += srcB[y*width + x+1];
            // +1*BC
            sumR += srcR[(y+1)*width + x];
            sumG += srcG[(y+1)*width + x];
            sumB += srcB[(y+1)*width + x];
            // +2*BR
            sumR += 2 * srcR[(y+1)*width + x+1];
            sumG += 2 * srcG[(y+1)*width + x+1];
            sumB += 2 * srcB[(y+1)*width + x+1];

            uint8_t* out = bytePixels + pos * 4;
            out[0] = (uint8_t)std::max(0, std::min(255, sumR + 128));
            out[1] = (uint8_t)std::max(0, std::min(255, sumG + 128));
            out[2] = (uint8_t)std::max(0, std::min(255, sumB + 128));
            out[3] = 0xFF;
        }
    }


    memcpy(bytePixels, bytePixels, width * 4);  // top row already there
    // Bottom row already there too

    env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0);
}


extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_vignetteSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
  jint *pixels = (jint*) env->GetPrimitiveArrayCritical(pixelArray, nullptr);
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

  env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0);
}


//this is new function->have not understand it ->currently from claude

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_comicSimd(
        JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {

    // 1. Zero-copy buffer pointer layout
    jint *pixels = (jint*) env->GetPrimitiveArrayCritical(pixelArray, nullptr);
    if (!pixels) return;

    int totalPixels = width * height;
    uint8_t* bytePixels = reinterpret_cast<uint8_t*>(pixels);

    // 2. Buffer replication to prevent memory tearing during lookups
    static std::vector<uint8_t> srcBufVec;
    srcBufVec.resize(totalPixels * 4);
    uint8_t* srcBuf = srcBufVec.data();
    memcpy(srcBuf, bytePixels, totalPixels * 4);

    static std::vector<uint8_t> lumaVec;
    lumaVec.resize(totalPixels);
    uint8_t* luma = lumaVec.data();

    // De-interleave the planar channels once (ARGB -> planar R, G, B).
    // Reusing these planes lets the 3x3 box-sum prepass run on contiguous
    // u8 streams rather than strided ARGB loads — critical for NEON throughput.
    static std::vector<uint8_t> planeRVec, planeGVec, planeBVec;
    planeRVec.resize(totalPixels); planeGVec.resize(totalPixels); planeBVec.resize(totalPixels);
    uint8_t* planeR = planeRVec.data();
    uint8_t* planeG = planeGVec.data();
    uint8_t* planeB = planeBVec.data();

    int simdEnd16 = totalPixels - (totalPixels % 16);
    uint8x8_t w_r = vdup_n_u8(77);
    uint8x8_t w_g = vdup_n_u8(150);
    uint8x8_t w_b = vdup_n_u8(29);
    for (int i = 0; i < simdEnd16; i += 16) {
        uint8x16x4_t argb = vld4q_u8(srcBuf + i * 4);
        // argb.val layout on little-endian ARGB int: [0]=B, [1]=G, [2]=R, [3]=A
        vst1q_u8(planeB + i, argb.val[0]);
        vst1q_u8(planeG + i, argb.val[1]);
        vst1q_u8(planeR + i, argb.val[2]);

        uint16x8_t g1 = vmull_u8(vget_low_u8(argb.val[2]), w_r);
        g1 = vmlal_u8(g1, vget_low_u8(argb.val[1]), w_g);
        g1 = vmlal_u8(g1, vget_low_u8(argb.val[0]), w_b);
        uint16x8_t g2 = vmull_u8(vget_high_u8(argb.val[2]), w_r);
        g2 = vmlal_u8(g2, vget_high_u8(argb.val[1]), w_g);
        g2 = vmlal_u8(g2, vget_high_u8(argb.val[0]), w_b);
        vst1q_u8(luma + i, vcombine_u8(vshrn_n_u16(g1, 8), vshrn_n_u16(g2, 8)));
    }
    for (int i = simdEnd16; i < totalPixels; i++) {
        uint8_t* p = srcBuf + i * 4;
        planeB[i] = p[0];
        planeG[i] = p[1];
        planeR[i] = p[2];
        luma[i] = (p[2]*77 + p[1]*150 + p[0]*29) >> 8;
    }

    // ---------------------------------------------------------------
    // BIG SPEEDUP: Precompute 3x3 box sums of R, G, B and of R^2, G^2, B^2
    // for every pixel. Each Kuwahara quadrant of radius 2 is exactly the
    // 3x3 block centered on one of the 4 diagonal neighbors (y±1, x±1),
    // so the inner Kuwahara work collapses from 4*9 = 36 pixel reads per
    // pixel (times 3 channels, times squaring) down to 4 indexed lookups.
    //
    // Separable 2-pass: horizontal sum-of-3, then vertical sum-of-3.
    // Sums fit in uint16 (max 9*255 = 2295), sum-of-squares fits in
    // uint32 (max 9*255*255 = 585225).

    static std::vector<uint16_t> hSumRVec, hSumGVec, hSumBVec;
    static std::vector<uint32_t> hSumR2Vec, hSumG2Vec, hSumB2Vec;
    hSumRVec.resize(totalPixels);  hSumGVec.resize(totalPixels);  hSumBVec.resize(totalPixels);
    hSumR2Vec.resize(totalPixels); hSumG2Vec.resize(totalPixels); hSumB2Vec.resize(totalPixels);
    uint16_t* hSumR = hSumRVec.data();   uint16_t* hSumG = hSumGVec.data();   uint16_t* hSumB = hSumBVec.data();
    uint32_t* hSumR2 = hSumR2Vec.data(); uint32_t* hSumG2 = hSumG2Vec.data(); uint32_t* hSumB2 = hSumB2Vec.data();

    static std::vector<uint16_t> boxRVec, boxGVec, boxBVec;
    static std::vector<uint32_t> boxR2Vec, boxG2Vec, boxB2Vec;
    boxRVec.resize(totalPixels);  boxGVec.resize(totalPixels);  boxBVec.resize(totalPixels);
    boxR2Vec.resize(totalPixels); boxG2Vec.resize(totalPixels); boxB2Vec.resize(totalPixels);
    uint16_t* boxR = boxRVec.data();   uint16_t* boxG = boxGVec.data();   uint16_t* boxB = boxBVec.data();
    uint32_t* boxR2 = boxR2Vec.data(); uint32_t* boxG2 = boxG2Vec.data(); uint32_t* boxB2 = boxB2Vec.data();

    auto horizBoxSum = [&](const uint8_t* plane, uint16_t* hS, uint32_t* hS2) {
        for (int y = 0; y < height; y++) {
            const uint8_t* row = plane + y * width;
            uint16_t* outS  = hS + y * width;
            uint32_t* outS2 = hS2 + y * width;

            // Borders: replicate (coerceIn)
            {
                int left  = row[0];
                int mid   = row[0];
                int right = row[1 < width ? 1 : 0];
                outS[0]  = left + mid + right;
                outS2[0] = left*left + mid*mid + right*right;
            }
            // Vectorize the interior x = 1 .. width-2 in blocks of 16.
            // We load three overlapping u8x16 vectors offset by -1, 0, +1 and
            // sum them as u16. Squares computed via vmull_u8 (u8*u8 -> u16).
            int x = 1;
            int vecEnd = width - 1 - ((width - 2) % 16);
            for (; x < vecEnd; x += 16) {
                uint8x16_t vL = vld1q_u8(row + x - 1);
                uint8x16_t vC = vld1q_u8(row + x);
                uint8x16_t vR = vld1q_u8(row + x + 1);

                // sums as u16x16
                uint16x8_t sLo = vaddl_u8(vget_low_u8(vL), vget_low_u8(vC));
                sLo = vaddw_u8(sLo, vget_low_u8(vR));
                uint16x8_t sHi = vaddl_u8(vget_high_u8(vL), vget_high_u8(vC));
                sHi = vaddw_u8(sHi, vget_high_u8(vR));
                vst1q_u16(outS + x, sLo);
                vst1q_u16(outS + x + 8, sHi);

                // squares: compute u16 = u8*u8 for each of L/C/R, then widen+sum to u32
                uint16x8_t sqL_lo = vmull_u8(vget_low_u8(vL), vget_low_u8(vL));
                uint16x8_t sqC_lo = vmull_u8(vget_low_u8(vC), vget_low_u8(vC));
                uint16x8_t sqR_lo = vmull_u8(vget_low_u8(vR), vget_low_u8(vR));
                uint16x8_t sqL_hi = vmull_u8(vget_high_u8(vL), vget_high_u8(vL));
                uint16x8_t sqC_hi = vmull_u8(vget_high_u8(vC), vget_high_u8(vC));
                uint16x8_t sqR_hi = vmull_u8(vget_high_u8(vR), vget_high_u8(vR));

                uint32x4_t s2_0 = vaddl_u16(vget_low_u16(sqL_lo),  vget_low_u16(sqC_lo));
                s2_0 = vaddw_u16(s2_0, vget_low_u16(sqR_lo));
                uint32x4_t s2_1 = vaddl_u16(vget_high_u16(sqL_lo), vget_high_u16(sqC_lo));
                s2_1 = vaddw_u16(s2_1, vget_high_u16(sqR_lo));
                uint32x4_t s2_2 = vaddl_u16(vget_low_u16(sqL_hi),  vget_low_u16(sqC_hi));
                s2_2 = vaddw_u16(s2_2, vget_low_u16(sqR_hi));
                uint32x4_t s2_3 = vaddl_u16(vget_high_u16(sqL_hi), vget_high_u16(sqC_hi));
                s2_3 = vaddw_u16(s2_3, vget_high_u16(sqR_hi));

                vst1q_u32(outS2 + x,      s2_0);
                vst1q_u32(outS2 + x + 4,  s2_1);
                vst1q_u32(outS2 + x + 8,  s2_2);
                vst1q_u32(outS2 + x + 12, s2_3);
            }
            for (; x < width - 1; x++) {
                int l = row[x - 1], c = row[x], r = row[x + 1];
                outS[x]  = l + c + r;
                outS2[x] = l*l + c*c + r*r;
            }
            // Right border
            if (width > 1) {
                int l = row[width - 2];
                int c = row[width - 1];
                int r = row[width - 1];
                outS[width - 1]  = l + c + r;
                outS2[width - 1] = l*l + c*c + r*r;
            }
        }
    };

    auto vertBoxSum = [&](const uint16_t* hS, const uint32_t* hS2,
                          uint16_t* boxS, uint32_t* boxS2) {
        for (int y = 0; y < height; y++) {
            int yTop = (y == 0) ? 0 : y - 1;
            int yBot = (y == height - 1) ? y : y + 1;
            const uint16_t* rowT = hS + yTop * width;
            const uint16_t* rowM = hS + y    * width;
            const uint16_t* rowB = hS + yBot * width;
            const uint32_t* rowT2 = hS2 + yTop * width;
            const uint32_t* rowM2 = hS2 + y    * width;
            const uint32_t* rowB2 = hS2 + yBot * width;
            uint16_t* outS  = boxS  + y * width;
            uint32_t* outS2 = boxS2 + y * width;

            int x = 0;
            int vecEnd = width - (width % 8);
            for (; x < vecEnd; x += 8) {
                uint16x8_t a = vld1q_u16(rowT + x);
                uint16x8_t b = vld1q_u16(rowM + x);
                uint16x8_t c = vld1q_u16(rowB + x);
                vst1q_u16(outS + x, vaddq_u16(vaddq_u16(a, b), c));

                uint32x4_t a2a = vld1q_u32(rowT2 + x);
                uint32x4_t a2b = vld1q_u32(rowT2 + x + 4);
                uint32x4_t b2a = vld1q_u32(rowM2 + x);
                uint32x4_t b2b = vld1q_u32(rowM2 + x + 4);
                uint32x4_t c2a = vld1q_u32(rowB2 + x);
                uint32x4_t c2b = vld1q_u32(rowB2 + x + 4);
                vst1q_u32(outS2 + x,     vaddq_u32(vaddq_u32(a2a, b2a), c2a));
                vst1q_u32(outS2 + x + 4, vaddq_u32(vaddq_u32(a2b, b2b), c2b));
            }
            for (; x < width; x++) {
                outS[x]  = rowT[x]  + rowM[x]  + rowB[x];
                outS2[x] = rowT2[x] + rowM2[x] + rowB2[x];
            }
        }
    };

    horizBoxSum(planeR, hSumR, hSumR2);
    horizBoxSum(planeG, hSumG, hSumG2);
    horizBoxSum(planeB, hSumB, hSumB2);
    vertBoxSum(hSumR, hSumR2, boxR, boxR2);
    vertBoxSum(hSumG, hSumG2, boxG, boxG2);
    vertBoxSum(hSumB, hSumB2, boxB, boxB2);

    uint16x8_t v_threshold16 = vdupq_n_u16(45);
    uint8x8x4_t blackColor;
    blackColor.val[0] = vdup_n_u8(0);
    blackColor.val[1] = vdup_n_u8(0);
    blackColor.val[2] = vdup_n_u8(0);
    blackColor.val[3] = vdup_n_u8(255);

    // 3. Primary Hybrid Filtering Math Setup
    int simdEnd = (width - 2) - ((width - 4) % 8);
    for (int y = 2; y < height - 2; y++) {
        // Pointers to the 4 quadrant-center rows (y-1 and y+1) used by Kuwahara.
        // Quadrant centers: (y-1, x-1), (y-1, x+1), (y+1, x-1), (y+1, x+1).
        const uint16_t* qTR = boxR  + (y - 1) * width;
        const uint16_t* qTG = boxG  + (y - 1) * width;
        const uint16_t* qTB = boxB  + (y - 1) * width;
        const uint32_t* qTR2 = boxR2 + (y - 1) * width;
        const uint32_t* qTG2 = boxG2 + (y - 1) * width;
        const uint32_t* qTB2 = boxB2 + (y - 1) * width;
        const uint16_t* qBR = boxR  + (y + 1) * width;
        const uint16_t* qBG = boxG  + (y + 1) * width;
        const uint16_t* qBB = boxB  + (y + 1) * width;
        const uint32_t* qBR2 = boxR2 + (y + 1) * width;
        const uint32_t* qBG2 = boxG2 + (y + 1) * width;
        const uint32_t* qBB2 = boxB2 + (y + 1) * width;

        for (int x = 2; x < simdEnd; x += 8) {
            uint8x8x4_t kuwaharaColor;

            // --- A. Kuwahara via precomputed 3x3 box sums (vectorized over 8 pixels) ---
            // For each of 4 quadrants q in {TL, TR, BL, BR}, load the 8-lane
            // box sum and sum-of-squares for R, G, B, then compute a scaled
            // variance proxy: var*81 = 9*sumSq - sum*sum (keeps everything int).
            // Pick the quadrant with minimum total variance per pixel.
            uint8_t kR[8], kG[8], kB[8], kA[8];

            // Offsets for the 4 quadrant centers relative to (y,x)
            // TL: (y-1, x-1), TR: (y-1, x+1), BL: (y+1, x-1), BR: (y+1, x+1)
#define LOAD_Q(sumRow, sqRow, xoff, outSum, outSq) \
                outSum[0] = vld1q_u16((sumRow) + (x + (xoff))); \
                outSq[0]  = vld1q_u32((sqRow)  + (x + (xoff))); \
                outSq[1]  = vld1q_u32((sqRow)  + (x + (xoff)) + 4);

            uint16x8_t sR_q[4], sG_q[4], sB_q[4];
            uint32x4_t sR2_q[4][2], sG2_q[4][2], sB2_q[4][2];

            LOAD_Q(qTR, qTR2, -1, (&sR_q[0]), sR2_q[0])
            LOAD_Q(qTG, qTG2, -1, (&sG_q[0]), sG2_q[0])
            LOAD_Q(qTB, qTB2, -1, (&sB_q[0]), sB2_q[0])
            LOAD_Q(qTR, qTR2, +1, (&sR_q[1]), sR2_q[1])
            LOAD_Q(qTG, qTG2, +1, (&sG_q[1]), sG2_q[1])
            LOAD_Q(qTB, qTB2, +1, (&sB_q[1]), sB2_q[1])
            LOAD_Q(qBR, qBR2, -1, (&sR_q[2]), sR2_q[2])
            LOAD_Q(qBG, qBG2, -1, (&sG_q[2]), sG2_q[2])
            LOAD_Q(qBB, qBB2, -1, (&sB_q[2]), sB2_q[2])
            LOAD_Q(qBR, qBR2, +1, (&sR_q[3]), sR2_q[3])
            LOAD_Q(qBG, qBG2, +1, (&sG_q[3]), sG2_q[3])
            LOAD_Q(qBB, qBB2, +1, (&sB_q[3]), sB2_q[3])
#undef LOAD_Q

            // Compute scaled variance = 9*sumSq - sum*sum for each channel.
            // sum  fits in u16 (max 2295), sum*sum fits in u32 (max ~5.26M).
            // 9*sumSq fits in u32 (max ~5.27M). Difference fits in i32.
            // Total over 3 channels fits in i32 easily (max ~3 * 5.27M).
            uint32_t totalVar[4][8];
            for (int q = 0; q < 4; q++) {
                uint32x4_t sR_lo = vmovl_u16(vget_low_u16(sR_q[q]));
                uint32x4_t sR_hi = vmovl_u16(vget_high_u16(sR_q[q]));
                uint32x4_t sG_lo = vmovl_u16(vget_low_u16(sG_q[q]));
                uint32x4_t sG_hi = vmovl_u16(vget_high_u16(sG_q[q]));
                uint32x4_t sB_lo = vmovl_u16(vget_low_u16(sB_q[q]));
                uint32x4_t sB_hi = vmovl_u16(vget_high_u16(sB_q[q]));

                uint32x4_t sqSumR_lo = vmulq_n_u32(sR2_q[q][0], 9);
                uint32x4_t sqSumR_hi = vmulq_n_u32(sR2_q[q][1], 9);
                uint32x4_t sqSumG_lo = vmulq_n_u32(sG2_q[q][0], 9);
                uint32x4_t sqSumG_hi = vmulq_n_u32(sG2_q[q][1], 9);
                uint32x4_t sqSumB_lo = vmulq_n_u32(sB2_q[q][0], 9);
                uint32x4_t sqSumB_hi = vmulq_n_u32(sB2_q[q][1], 9);

                uint32x4_t meanSqR_lo = vmulq_u32(sR_lo, sR_lo);
                uint32x4_t meanSqR_hi = vmulq_u32(sR_hi, sR_hi);
                uint32x4_t meanSqG_lo = vmulq_u32(sG_lo, sG_lo);
                uint32x4_t meanSqG_hi = vmulq_u32(sG_hi, sG_hi);
                uint32x4_t meanSqB_lo = vmulq_u32(sB_lo, sB_lo);
                uint32x4_t meanSqB_hi = vmulq_u32(sB_hi, sB_hi);

                // varScaled = 9*sumSq - sum*sum  (>= 0 always)
                uint32x4_t vR_lo = vsubq_u32(sqSumR_lo, meanSqR_lo);
                uint32x4_t vR_hi = vsubq_u32(sqSumR_hi, meanSqR_hi);
                uint32x4_t vG_lo = vsubq_u32(sqSumG_lo, meanSqG_lo);
                uint32x4_t vG_hi = vsubq_u32(sqSumG_hi, meanSqG_hi);
                uint32x4_t vB_lo = vsubq_u32(sqSumB_lo, meanSqB_lo);
                uint32x4_t vB_hi = vsubq_u32(sqSumB_hi, meanSqB_hi);

                uint32x4_t tot_lo = vaddq_u32(vaddq_u32(vR_lo, vG_lo), vB_lo);
                uint32x4_t tot_hi = vaddq_u32(vaddq_u32(vR_hi, vG_hi), vB_hi);
                vst1q_u32(totalVar[q],     tot_lo);
                vst1q_u32(totalVar[q] + 4, tot_hi);
            }

            // Pick the best quadrant per pixel and take its mean (= sum/9 rounded to int).
            // This preserves the baseline semantics (meanR,G,B are (int)(sum/9.0f)).
            uint16_t sumR_arr[4][8], sumG_arr[4][8], sumB_arr[4][8];
            for (int q = 0; q < 4; q++) {
                vst1q_u16(sumR_arr[q], sR_q[q]);
                vst1q_u16(sumG_arr[q], sG_q[q]);
                vst1q_u16(sumB_arr[q], sB_q[q]);
            }

            for (int k = 0; k < 8; k++) {
                uint32_t minVar = totalVar[0][k];
                int bestQ = 0;
                if (totalVar[1][k] < minVar) { minVar = totalVar[1][k]; bestQ = 1; }
                if (totalVar[2][k] < minVar) { minVar = totalVar[2][k]; bestQ = 2; }
                if (totalVar[3][k] < minVar) { minVar = totalVar[3][k]; bestQ = 3; }

                int bestR = sumR_arr[bestQ][k] / 9;
                int bestG = sumG_arr[bestQ][k] / 9;
                int bestB = sumB_arr[bestQ][k] / 9;

                bestR = std::min(255, (bestR / 64) * 64 + 32);
                bestG = std::min(255, (bestG / 64) * 64 + 32);
                bestB = std::min(255, (bestB / 64) * 64 + 32);

                uint8_t* pAlpha = srcBuf + ((y * width + x + k) * 4);
                kA[k] = pAlpha[3];
                kR[k] = bestR;
                kG[k] = bestG;
                kB[k] = bestB;
            }

            kuwaharaColor.val[0] = vld1_u8(kB);
            kuwaharaColor.val[1] = vld1_u8(kG);
            kuwaharaColor.val[2] = vld1_u8(kR);
            kuwaharaColor.val[3] = vld1_u8(kA);

            // --- B. Sobel Edge (SIMD NEON Math) ---
            uint16x8_t tl = vmovl_u8(vld1_u8(luma + (y - 1) * width + x - 1));
            uint16x8_t tc = vmovl_u8(vld1_u8(luma + (y - 1) * width + x));
            uint16x8_t tr = vmovl_u8(vld1_u8(luma + (y - 1) * width + x + 1));

            uint16x8_t ml = vmovl_u8(vld1_u8(luma + y * width + x - 1));
            uint16x8_t mr = vmovl_u8(vld1_u8(luma + y * width + x + 1));

            uint16x8_t bl = vmovl_u8(vld1_u8(luma + (y + 1) * width + x - 1));
            uint16x8_t bc = vmovl_u8(vld1_u8(luma + (y + 1) * width + x));
            uint16x8_t br = vmovl_u8(vld1_u8(luma + (y + 1) * width + x + 1));

            uint16x8_t col_r = vaddq_u16(tr, br);
            col_r = vaddq_u16(col_r, vshlq_n_u16(mr, 1));
            uint16x8_t col_l = vaddq_u16(tl, bl);
            col_l = vaddq_u16(col_l, vshlq_n_u16(ml, 1));

            uint16x8_t row_b = vaddq_u16(bl, br);
            row_b = vaddq_u16(row_b, vshlq_n_u16(bc, 1));
            uint16x8_t row_t = vaddq_u16(tl, tr);
            row_t = vaddq_u16(row_t, vshlq_n_u16(tc, 1));

            uint16x8_t abs_gx = vabdq_u16(col_r, col_l);
            uint16x8_t abs_gy = vabdq_u16(row_b, row_t);

            uint16x8_t mag16 = vaddq_u16(abs_gx, abs_gy);

            // Mask mapping: if magnitude arrays > 60 -> mask bits become 1
            uint16x8_t cmp16 = vcgtq_u16(mag16, v_threshold16);

            // Shave evaluation to 8 bits to cleanly interleave over ARGB layout
            uint8x8_t mask8 = vmovn_u16(cmp16);

            // --- C. The Blend Component (Bit Selection using `vbsl_u8`) ---
            uint8x8x4_t out_argb;
            out_argb.val[0] = vbsl_u8(mask8, blackColor.val[0], kuwaharaColor.val[0]);
            out_argb.val[1] = vbsl_u8(mask8, blackColor.val[1], kuwaharaColor.val[1]);
            out_argb.val[2] = vbsl_u8(mask8, blackColor.val[2], kuwaharaColor.val[2]);
            out_argb.val[3] = vbsl_u8(mask8, blackColor.val[3], kuwaharaColor.val[3]);

            vst4_u8(bytePixels + (y * width + x) * 4, out_argb);
        }

        // Tail loop evaluation block handling margins outside aligned block ranges...
    }

    int gxKernel[3][3] = { {-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1} };
    int gyKernel[3][3] = { {-1, -2, -1}, {0, 0, 0}, {1, 2, 1} };

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            if (y >= 2 && y < height - 2 && x >= 2 && x < simdEnd) {
                continue;
            }

            // Kuwahara
            float minVar = 999999999.0f;
            int bestR = 0, bestG = 0, bestB = 0;
            int quadrants[4][4] = {
                    {-2, 0, -2, 0}, { 0, 2, -2, 0},
                    {-2, 0,  0, 2}, { 0, 2,  0, 2}
            };
            for (int q = 0; q < 4; q++) {
                int sumR = 0, sumG = 0, sumB = 0;
                float sumSqR = 0.0f, sumSqG = 0.0f, sumSqB = 0.0f;
                for (int ky = quadrants[q][2]; ky <= quadrants[q][3]; ky++) {
                    for (int kx = quadrants[q][0]; kx <= quadrants[q][1]; kx++) {
                        int nx = std::max(0, std::min(width - 1, x + kx));
                        int ny = std::max(0, std::min(height - 1, y + ky));
                        uint8_t* p = srcBuf + (ny * width + nx) * 4;
                        int b = p[0], g = p[1], r = p[2];
                        sumR += r; sumG += g; sumB += b;
                        sumSqR += r*r; sumSqG += g*g; sumSqB += b*b;
                    }
                }
                float meanR = sumR / 9.0f;
                float meanG = sumG / 9.0f;
                float meanB = sumB / 9.0f;
                float varR = (sumSqR / 9.0f) - (meanR * meanR);
                float varG = (sumSqG / 9.0f) - (meanG * meanG);
                float varB = (sumSqB / 9.0f) - (meanB * meanB);
                float totalVar = varR + varG + varB;
                if (totalVar < minVar) {
                    minVar = totalVar;
                    bestR = (int)meanR;
                    bestG = (int)meanG;
                    bestB = (int)meanB;
                }
            }

            // Sobel
            int sumX = 0, sumY = 0;
            for (int ky = -1; ky <= 1; ky++) {
                for (int kx = -1; kx <= 1; kx++) {
                    int nx = std::max(0, std::min(width - 1, x + kx));
                    int ny = std::max(0, std::min(height - 1, y + ky));
                    int pixelVal = luma[ny * width + nx];
                    sumX += pixelVal * gxKernel[ky + 1][kx + 1];
                    sumY += pixelVal * gyKernel[ky + 1][kx + 1];
                }
            }
            int mag = std::abs(sumX) + std::abs(sumY);

            uint8_t a = srcBuf[(y * width + x) * 4 + 3];
            uint8_t* out = bytePixels + ((y * width + x) * 4);
            if (mag > 45) {
                out[0] = 0; out[1] = 0; out[2] = 0; out[3] = a;
            } else {
                bestR = std::min(255, (bestR / 64) * 64 + 32);
                bestG = std::min(255, (bestG / 64) * 64 + 32);
                bestB = std::min(255, (bestB / 64) * 64 + 32);
                out[0] = bestB; out[1] = bestG; out[2] = bestR; out[3] = a;
            }
        }
    }

    // Static vectors manage their own memory — no delete needed.

    // 4. Important Release Signal releasing the memory latch tied to the Java GC loop
    env->ReleasePrimitiveArrayCritical(pixelArray, pixels, 0);
}


