#ifndef PIPELINE_NEON_CORE_H
#define PIPELINE_NEON_CORE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Samsung Galaxy A56 (Exynos 1580 / Cortex-A720) için
 * Donanım Seviyesinde ARM64 NEON SIMD Görüntü Birleştirme ve Tonlama Motoru.
 *
 * @param ref_rgba      Referans kare piksel dizisi (RGBA 8888)
 * @param curr_rgba     Mevcut kare piksel dizisi (RGBA 8888)
 * @param out_rgba      Çıktı birleştirilmiş kare piksel dizisi (RGBA 8888)
 * @param pixel_count   Toplam piksel sayısı (width * height)
 * @param mad_threshold Hayalet engelleme (MAD) eşik değeri (0-255 arası, varsayılan ~35)
 * @param warmth_gain_r Kırmızı kanal sıcaklık kazancı (1.0 = nötr, 1.035 = Apple/Leica altın sıcaklık)
 * @param warmth_gain_b Mavi kanal sıcaklık kazancı (1.0 = nötr, 0.985 = hafif sıcak gökyüzü)
 */
void process_robust_merge_neon(
    const uint8_t* ref_rgba,
    const uint8_t* curr_rgba,
    uint8_t* out_rgba,
    int pixel_count,
    uint8_t mad_threshold,
    float warmth_gain_r,
    float warmth_gain_b
);

/**
 * 32-bit Kayan Noktalı Çoklu Kare Akümülatör Birleştirici (Multi-frame Temporal Accumulator).
 * N kareyi donanımsal SIMD ile akümülatöre ekler ve hayaletleri eler.
 */
void process_temporal_accumulate_neon(
    const uint8_t* curr_rgba,
    const uint8_t* ref_rgba,
    float* accum_buffer,
    float* weight_buffer,
    int pixel_count,
    float threshold_f32,
    float ghost_weight
);

/**
 * Akümülatördeki Float32 veriyi Leica S-Curve ve Highlight Rolloff ile normalize edip
 * 8-bit RGBA çıkışa dönüştürür.
 */
void finalize_accumulator_leica_neon(
    const float* accum_buffer,
    const float* weight_buffer,
    uint8_t* out_rgba,
    int pixel_count,
    float gamma,
    float contrast
);

/**
 * ARM64 NEON Semantik Maske & Doku Harmanlayıcı (Semantic Masked Blender).
 * BiSeNetv2 veya Smart Grain maskesini sıfır kopyayla RGBA piksellere 128-bit vektörlerle giydirir:
 * out = (fg * mask + bg * (255 - mask)) >> 8
 */
void process_semantic_blend_neon(
    const uint8_t* bg_rgba,
    const uint8_t* fg_rgba,
    const uint8_t* mask_rgba,
    uint8_t* out_rgba,
    int pixel_count
);

#ifdef __cplusplus
}
#endif

#endif // PIPELINE_NEON_CORE_H
