#include <jni.h>
#include <arm_neon.h>
#include <algorithm>
#include <cmath>



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
    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    int totalPixels = width * height;
    jint* tempPixels = new jint[totalPixels];
    uint8_t* bytePixels = reinterpret_cast<uint8_t*>(pixels);
    uint8_t* tempBytePixels = reinterpret_cast<uint8_t*>(tempPixels);

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

    delete[] tempPixels;
    env->ReleaseIntArrayElements(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_sobelEdgeSimd(JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {
    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    int totalPixels = width * height;
    uint8_t* grayPixels = new uint8_t[totalPixels];
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

    delete[] grayPixels;
    env->ReleaseIntArrayElements(pixelArray, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_acceleratedcamera_NativeLib_embossSimd(
        JNIEnv *env, jobject, jintArray pixelArray, jint width, jint height) {

    jint *pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (!pixels) return;

    int total = width * height;
    uint8_t* bytePixels = reinterpret_cast<uint8_t*>(pixels);

    auto *srcR = new uint8_t[total];
    auto *srcG = new uint8_t[total];
    auto *srcB = new uint8_t[total];

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

    delete[] srcR;
    delete[] srcG;
    delete[] srcB;

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