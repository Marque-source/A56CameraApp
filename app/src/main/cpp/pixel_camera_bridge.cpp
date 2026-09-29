#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include "pipeline_neon_core.h"

#define TAG "NeonCameraBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/**
 * Kotlin'den Bitmap'leri doğrudan kilitleyip sıfır kopyayla NEON SIMD Robust Merge çalıştırır.
 */
JNIEXPORT jboolean JNICALL
Java_com_a56camera_pipeline_NeonPipelineEngine_nativeRobustMerge(
    JNIEnv *env,
    jobject /* this */,
    jobject ref_bitmap,
    jobject curr_bitmap,
    jobject out_bitmap,
    jint width,
    jint height,
    jint mad_threshold
) {
    void *ref_pixels = nullptr;
    void *curr_pixels = nullptr;
    void *out_pixels = nullptr;

    if (AndroidBitmap_lockPixels(env, ref_bitmap, &ref_pixels) < 0) {
        LOGE("ref_bitmap lock başarısız");
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, curr_bitmap, &curr_pixels) < 0) {
        AndroidBitmap_unlockPixels(env, ref_bitmap);
        LOGE("curr_bitmap lock başarısız");
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, out_bitmap, &out_pixels) < 0) {
        AndroidBitmap_unlockPixels(env, ref_bitmap);
        AndroidBitmap_unlockPixels(env, curr_bitmap);
        LOGE("out_bitmap lock başarısız");
        return JNI_FALSE;
    }

    int pixel_count = width * height;

    // ARM64 NEON SIMD donanım motorunu ateşle!
    process_robust_merge_neon(
        static_cast<const uint8_t*>(ref_pixels),
        static_cast<const uint8_t*>(curr_pixels),
        static_cast<uint8_t*>(out_pixels),
        pixel_count,
        static_cast<uint8_t>(mad_threshold),
        1.035f,
        0.985f
    );

    AndroidBitmap_unlockPixels(env, ref_bitmap);
    AndroidBitmap_unlockPixels(env, curr_bitmap);
    AndroidBitmap_unlockPixels(env, out_bitmap);

    return JNI_TRUE;
}

/**
 * Direct ByteBuffer üzerinden donanımsal NEON birleştirme (Zero-Copy)
 */
JNIEXPORT jboolean JNICALL
Java_com_a56camera_pipeline_NeonPipelineEngine_nativeMergeBuffers(
    JNIEnv *env,
    jobject /* this */,
    jobject ref_buf,
    jobject curr_buf,
    jobject out_buf,
    jint pixel_count,
    jint mad_threshold
) {
    uint8_t *ref_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(ref_buf));
    uint8_t *curr_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(curr_buf));
    uint8_t *out_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(out_buf));

    if (!ref_ptr || !curr_ptr || !out_ptr) {
        LOGE("DirectByteBuffer adresleri geçersiz!");
        return JNI_FALSE;
    }

    process_robust_merge_neon(
        ref_ptr,
        curr_ptr,
        out_ptr,
        pixel_count,
        static_cast<uint8_t>(mad_threshold),
        1.035f,
        0.985f
    );

    return JNI_TRUE;
}

/**
 * ARM64 NEON Semantik Maske Harmanlayıcı JNI Köprüsü
 */
JNIEXPORT jboolean JNICALL
Java_com_a56camera_pipeline_NeonPipelineEngine_nativeSemanticBlend(
    JNIEnv *env,
    jobject /* this */,
    jobject bg_bitmap,
    jobject fg_bitmap,
    jobject mask_bitmap,
    jobject out_bitmap,
    jint width,
    jint height
) {
    void *bg_pixels = nullptr;
    void *fg_pixels = nullptr;
    void *mask_pixels = nullptr;
    void *out_pixels = nullptr;

    if (AndroidBitmap_lockPixels(env, bg_bitmap, &bg_pixels) < 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, fg_bitmap, &fg_pixels) < 0) {
        AndroidBitmap_unlockPixels(env, bg_bitmap);
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, mask_bitmap, &mask_pixels) < 0) {
        AndroidBitmap_unlockPixels(env, bg_bitmap);
        AndroidBitmap_unlockPixels(env, fg_bitmap);
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, out_bitmap, &out_pixels) < 0) {
        AndroidBitmap_unlockPixels(env, bg_bitmap);
        AndroidBitmap_unlockPixels(env, fg_bitmap);
        AndroidBitmap_unlockPixels(env, mask_bitmap);
        return JNI_FALSE;
    }

    int pixel_count = width * height;
    process_semantic_blend_neon(
        static_cast<const uint8_t*>(bg_pixels),
        static_cast<const uint8_t*>(fg_pixels),
        static_cast<const uint8_t*>(mask_pixels),
        static_cast<uint8_t*>(out_pixels),
        pixel_count
    );

    AndroidBitmap_unlockPixels(env, bg_bitmap);
    AndroidBitmap_unlockPixels(env, fg_bitmap);
    AndroidBitmap_unlockPixels(env, mask_bitmap);
    AndroidBitmap_unlockPixels(env, out_bitmap);

    return JNI_TRUE;
}

} // extern "C"

