/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "framefx"

#include <framefx/channel.h>
#include <jni.h>
#include <linux/futex.h>
#include <nativehelper/JNIHelp.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <climits>
#include <cstring>

namespace android {
namespace {

using framefx::kChannelSize;
using framefx::kSequenceOffset;
using framefx::kStatusSize;

uint8_t* region(JNIEnv* env, jobject buffer, size_t size) {
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(size)) {
        return nullptr;
    }
    return base;
}

// Rewrites a channel under its sequence lock, odd while the bytes change, then wakes every
// game process sleeping on the sequence word. The broker is the only writer.
jboolean nativePublish(JNIEnv* env, jclass, jobject channel, jbyteArray image) {
    uint8_t* base = region(env, channel, kChannelSize);
    if (base == nullptr || env->GetArrayLength(image) != static_cast<jsize>(kChannelSize)) {
        return JNI_FALSE;
    }
    uint8_t bytes[kChannelSize];
    env->GetByteArrayRegion(image, 0, kChannelSize, reinterpret_cast<jbyte*>(bytes));
    auto* word = reinterpret_cast<uint32_t*>(base + kSequenceOffset);
    const uint32_t sequence = __atomic_load_n(word, __ATOMIC_RELAXED);
    __atomic_store_n(word, sequence + 1, __ATOMIC_RELAXED);
    std::atomic_thread_fence(std::memory_order_release);
    memcpy(base, bytes, kSequenceOffset);
    const size_t rest = kSequenceOffset + sizeof(uint32_t);
    memcpy(base + rest, bytes + rest, kChannelSize - rest);
    __atomic_store_n(word, sequence + 2, __ATOMIC_RELEASE);
    syscall(SYS_futex, word, FUTEX_WAKE, INT_MAX, nullptr, nullptr, 0);
    return JNI_TRUE;
}

// A consistent copy of a status page its game may be writing.
jboolean nativeSnapshot(JNIEnv* env, jclass, jobject page, jbyteArray out) {
    const uint8_t* base = region(env, page, kStatusSize);
    if (base == nullptr || env->GetArrayLength(out) != static_cast<jsize>(kStatusSize)) {
        return JNI_FALSE;
    }
    const auto* word = reinterpret_cast<const uint32_t*>(base + kSequenceOffset);
    uint8_t copy[kStatusSize];
    for (int attempt = 0; attempt < 4; attempt++) {
        const uint32_t before = __atomic_load_n(word, __ATOMIC_ACQUIRE);
        if ((before & 1u) != 0) continue;
        memcpy(copy, base, kStatusSize);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (__atomic_load_n(word, __ATOMIC_RELAXED) == before) {
            env->SetByteArrayRegion(out, 0, kStatusSize, reinterpret_cast<const jbyte*>(copy));
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

const JNINativeMethod kMethods[] = {
        {"nativePublish", "(Ljava/nio/ByteBuffer;[B)Z", reinterpret_cast<void*>(nativePublish)},
        {"nativeSnapshot", "(Ljava/nio/ByteBuffer;[B)Z",
         reinterpret_cast<void*>(nativeSnapshot)},
};

} // namespace

int register_android_server_yasvara_framefx_FramefxBroker(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/yasvara/framefx/FramefxBroker",
                                    kMethods, NELEM(kMethods));
}

} // namespace android
