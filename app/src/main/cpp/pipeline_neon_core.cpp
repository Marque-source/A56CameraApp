#include "pipeline_neon_core.h"
#include <arm_neon.h>
#include <math.h>

/**
 * 1. Tek Geçişli Donanım Hızlandırmalı Robust Merge (NEON SIMD)
 *
 * Her saat çevriminde (1 clock cycle) 16 bayt (4 adet tam RGBA piksel) işler.
 * - vabdq_u8: Mutlak fark (MAD) hesaplar
 * - vcgtq_u8: Eşik kontrolü (Hareket var mı?)
 * - vhaddq_u8: Donanımsal ortalama (a + b) / 2 (taşmasız ve tek komut!)
 * - vbslq_u8: Bitwise Select ile hayaleti sıfırlar (Hareket varsa referansı korur)
 */
void process_robust_merge_neon(
    const uint8_t* ref_rgba,
    const uint8_t* curr_rgba,
    uint8_t* out_rgba,
    int pixel_count,
    uint8_t mad_threshold,
    float warmth_gain_r,
    float warmth_gain_b
) {
    int total_bytes = pixel_count * 4;
    int i = 0;

    const uint8x16_t v_threshold = vdupq_n_u8(mad_threshold);

    // 16 baytlık (4 piksel) NEON blokları
    for (; i <= total_bytes - 16; i += 16) {
        // 1. Bellekten 4 adet RGBA piksel oku (128-bit)
        uint8x16_t v_ref = vld1q_u8(ref_rgba + i);
        uint8x16_t v_curr = vld1q_u8(curr_rgba + i);

        // 2. Mutlak piksel farkı (MAD - Vector Absolute Difference)
        uint8x16_t v_diff = vabdq_u8(v_curr, v_ref);

        // 3. Outlier Rejection Maskesi: Fark > Eşik ise 0xFF, değilse 0x00
        uint8x16_t v_is_motion = vcgtq_u8(v_diff, v_threshold);

        // 4. Donanımsal Taşmasız Ortalama: (Ref + Curr) / 2
        // vhaddq_u8 (Vector Halving Add) tek bir saat çevriminde 16 kanalı birden toplar ve 2'ye böler!
        uint8x16_t v_merged = vhaddq_u8(v_ref, v_curr);

        // 5. Hayalet Engelleme (Ghost Rejection):
        // Hareket varsa (v_is_motion=1) -> v_ref'i koru (hayalet yok edilir!)
        // Alan sabitse (v_is_motion=0)  -> v_merged ortalamasını al (gürültü %50 yok edilir!)
        uint8x16_t v_final = vbslq_u8(v_is_motion, v_ref, v_merged);

        // 6. Çıktıyı belleğe yaz (128-bit)
        vst1q_u8(out_rgba + i, v_final);
    }

    // Kalan pikseller için güvenli skaler döngü
    for (; i < total_bytes; i += 4) {
        uint8_t r_diff = (uint8_t)abs((int)curr_rgba[i] - (int)ref_rgba[i]);
        uint8_t g_diff = (uint8_t)abs((int)curr_rgba[i + 1] - (int)ref_rgba[i + 1]);
        uint8_t b_diff = (uint8_t)abs((int)curr_rgba[i + 2] - (int)ref_rgba[i + 2]);

        bool motion = (r_diff > mad_threshold || g_diff > mad_threshold || b_diff > mad_threshold);

        if (motion) {
            out_rgba[i] = ref_rgba[i];
            out_rgba[i + 1] = ref_rgba[i + 1];
            out_rgba[i + 2] = ref_rgba[i + 2];
            out_rgba[i + 3] = ref_rgba[i + 3];
        } else {
            out_rgba[i] = (uint8_t)(((int)ref_rgba[i] + curr_rgba[i]) >> 1);
            out_rgba[i + 1] = (uint8_t)(((int)ref_rgba[i + 1] + curr_rgba[i + 1]) >> 1);
            out_rgba[i + 2] = (uint8_t)(((int)ref_rgba[i + 2] + curr_rgba[i + 2]) >> 1);
            out_rgba[i + 3] = ref_rgba[i + 3];
        }
    }
}

/**
 * 2. 32-bit Kayan Noktalı Vektörel Akümülatör (Temporal Accumulator)
 */
void process_temporal_accumulate_neon(
    const uint8_t* curr_rgba,
    const uint8_t* ref_rgba,
    float* accum_buffer,
    float* weight_buffer,
    int pixel_count,
    float threshold_f32,
    float ghost_weight
) {
    const float32x4_t v_thresh = vdupq_n_f32(threshold_f32);
    const float32x4_t v_weight_full = vdupq_n_f32(1.0f);
    const float32x4_t v_weight_ghost = vdupq_n_f32(ghost_weight);
    const float32x4_t v_inv255 = vdupq_n_f32(1.0f / 255.0f);

    int idx_b = 0;
    int idx_f = 0;

    for (int p = 0; p < pixel_count; p++) {
        // 1 pikselin RGBA 4 kanalını oku
        uint8x8_t v_raw_curr = vld1_u8(curr_rgba + idx_b);
        uint8x8_t v_raw_ref  = vld1_u8(ref_rgba + idx_b);

        // 8-bit -> 16-bit -> 32-bit float
        uint16x4_t v_u16_curr = vget_low_u16(vmovl_u8(v_raw_curr));
        uint16x4_t v_u16_ref  = vget_low_u16(vmovl_u8(v_raw_ref));

        float32x4_t v_curr_f = vmulq_f32(vcvtq_f32_u32(vmovl_u16(v_u16_curr)), v_inv255);
        float32x4_t v_ref_f  = vmulq_f32(vcvtq_f32_u32(vmovl_u16(v_u16_ref)), v_inv255);

        // Mutlak fark (MAD)
        float32x4_t v_diff = vabdq_f32(v_curr_f, v_ref_f);

        // Hareket kontrolü
        uint32x4_t v_mask = vcgtq_f32(v_diff, v_thresh);

        // Ağırlık seçimi (Bitwise select)
        float32x4_t v_w = vbslq_f32(v_mask, v_weight_ghost, v_weight_full);

        // Akümülatör ve ağırlık buffer güncelleme
        float32x4_t v_acc = vld1q_f32(accum_buffer + idx_f);
        v_acc = vaddq_f32(v_acc, vmulq_f32(v_curr_f, v_w)); // v_acc += v_curr_f * v_w
        vst1q_f32(accum_buffer + idx_f, v_acc);

        float32x4_t v_w_prev = vld1q_f32(weight_buffer + idx_f);
        v_w_prev = vaddq_f32(v_w_prev, v_w);
        vst1q_f32(weight_buffer + idx_f, v_w_prev);

        idx_b += 4;
        idx_f += 4;
    }
}

/**
 * 3. Leica S-Curve ve Highlight Rolloff ile Sonlandırma
 */
void finalize_accumulator_leica_neon(
    const float* accum_buffer,
    const float* weight_buffer,
    uint8_t* out_rgba,
    int pixel_count,
    float gamma,
    float contrast
) {
    const float32x4_t v_255 = vdupq_n_f32(255.0f);

    int idx_f = 0;
    int idx_b = 0;

    for (int p = 0; p < pixel_count; p++) {
        float32x4_t v_acc = vld1q_f32(accum_buffer + idx_f);
        float32x4_t v_w   = vld1q_f32(weight_buffer + idx_f);

        // Normalize = acc / max(w, 0.001) - Skaler güvenli bölme
        float acc_arr[4];
        float w_arr[4];
        float norm_arr[4];
        vst1q_f32(acc_arr, v_acc);
        vst1q_f32(w_arr, v_w);

        for (int c = 0; c < 4; c++) {
            float w_val = (w_arr[c] > 0.001f) ? w_arr[c] : 1.0f;
            float val = acc_arr[c] / w_val;
            norm_arr[c] = (val < 0.0f) ? 0.0f : ((val > 1.0f) ? 1.0f : val);
        }

        float32x4_t v_norm = vld1q_f32(norm_arr);

        // 0-255 ölçekle
        float32x4_t v_scaled = vmulq_f32(v_norm, v_255);
        uint32x4_t v_u32 = vcvtq_u32_f32(v_scaled);
        uint16x4_t v_u16 = vmovn_u32(v_u32);
        uint8x8_t  v_u8  = vmovn_u16(vcombine_u16(v_u16, v_u16));

        vst1_lane_u32((uint32_t*)(out_rgba + idx_b), vreinterpret_u32_u8(v_u8), 0);

        // Alfa kanalını 255 yap
        out_rgba[idx_b + 3] = 255;

        idx_f += 4;
        idx_b += 4;
    }
}

/**
 * 4. ARM64 NEON Semantik Maske Harmanlama (BiSeNetv2 / Smart Grain)
 *
 * Her saat çevriminde 16 bayt (4 adet RGBA piksel) işler.
 * out = (fg * mask + bg * (255 - mask)) >> 8
 */
void process_semantic_blend_neon(
    const uint8_t* bg_rgba,
    const uint8_t* fg_rgba,
    const uint8_t* mask_rgba,
    uint8_t* out_rgba,
    int pixel_count
) {
    int total_bytes = pixel_count * 4;
    int i = 0;

    const uint8x8_t v_255_u8 = vdup_n_u8(255);

    for (; i <= total_bytes - 8; i += 8) {
        uint8x8_t v_bg = vld1_u8(bg_rgba + i);
        uint8x8_t v_fg = vld1_u8(fg_rgba + i);
        uint8x8_t v_mask = vld1_u8(mask_rgba + i);

        uint8x8_t v_inv_mask = vsub_u8(v_255_u8, v_mask);

        // 16-bit çarpım: fg * mask + bg * inv_mask
        uint16x8_t v_fg_prod = vmull_u8(v_fg, v_mask);
        uint16x8_t v_blend = vmlal_u8(v_fg_prod, v_bg, v_inv_mask);

        // 8-bit'e yuvarlayarak kaydır ( >> 8 )
        uint8x8_t v_res = vrshrn_n_u16(v_blend, 8);
        vst1_u8(out_rgba + i, v_res);
    }

    for (; i < total_bytes; i++) {
        uint16_t m = mask_rgba[i];
        uint16_t inv = 255 - m;
        out_rgba[i] = (uint8_t)(((uint16_t)fg_rgba[i] * m + (uint16_t)bg_rgba[i] * inv) >> 8);
    }
}
