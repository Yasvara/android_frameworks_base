/*
 * Copyright (C) 2026 The Yasvara Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

// Pure Tabir decisions used by Zygote. No JNI, so the host test can link them.
// onyx is arm64-only. A protected app never maps Zygisk and keeps no module fd.

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <string>
#include <vector>

namespace yasvara {
namespace tabir {

constexpr uid_t kUserOffset = 100000;
constexpr uid_t kAppStart = 10000;
constexpr uid_t kIsolatedStart = 90000;
constexpr uid_t kIsolatedEnd = 99999;
constexpr uint8_t kScopeAllNonDenylist = 0;

struct ZygiskModuleView {
    bool has_abi;
    uint8_t scope_mode;
    const uint32_t* scope_uids;
    size_t scope_count;
};

inline bool UidInSorted(const uint32_t* ids, size_t count, uid_t uid) {
    if (ids == nullptr || count == 0) return false;
    const uint32_t full = static_cast<uint32_t>(uid);
    const uint32_t appid = static_cast<uint32_t>(uid % kUserOffset);
    size_t lo = 0;
    size_t hi = count;
    auto has = [&](uint32_t needle) {
        lo = 0;
        hi = count;
        while (lo < hi) {
            const size_t mid = lo + (hi - lo) / 2;
            if (ids[mid] == needle) return true;
            if (ids[mid] < needle) lo = mid + 1;
            else hi = mid;
        }
        return false;
    };
    return has(full) || has(appid);
}

inline bool RuntimeActive(bool any_enrolled, bool any_mounts, bool zygisk_enabled,
                          bool zygisk_safe_bypass, size_t zygisk_modules) {
    return any_enrolled || any_mounts ||
           (zygisk_enabled && !zygisk_safe_bypass && zygisk_modules > 0);
}

// Copies a module .so into a sealed memfd. The Zygote parent holds one per module so every
// in-scope child maps the same pages. Sealed: a child that reaches the fd cannot rewrite the
// text other apps will map. Returns -1 on any failure (the child then copies privately).
inline int ShareModuleText(const char* path) {
    if (path == nullptr || path[0] == '\0') return -1;
    const int in = TEMP_FAILURE_RETRY(open(path, O_RDONLY | O_CLOEXEC));
    if (in < 0) return -1;
    constexpr unsigned kCloexec = 0x0001U;       // MFD_CLOEXEC
    constexpr unsigned kAllowSealing = 0x0002U;  // MFD_ALLOW_SEALING
    const int mfd = static_cast<int>(syscall(__NR_memfd_create, "jit-cache", kCloexec | kAllowSealing));
    if (mfd < 0) {
        close(in);
        return -1;
    }
    bool ok = true;
    char buf[1 << 16];
    for (;;) {
        const ssize_t n = TEMP_FAILURE_RETRY(read(in, buf, sizeof(buf)));
        if (n <= 0) {
            ok = n == 0;
            break;
        }
        for (ssize_t off = 0; off < n;) {
            const ssize_t w = TEMP_FAILURE_RETRY(write(mfd, buf + off, static_cast<size_t>(n - off)));
            if (w <= 0) {
                ok = false;
                break;
            }
            off += w;
        }
        if (!ok) break;
    }
    close(in);
    constexpr int kSeals = 0x0001 | 0x0002 | 0x0004 | 0x0008;  // SEAL, SHRINK, GROW, WRITE
    if (!ok || fcntl(mfd, 1033 /* F_ADD_SEALS */, kSeals) != 0) {
        close(mfd);
        return -1;
    }
    return mfd;
}

// A shared copy and the file it was taken from.
struct SharedText {
    std::string path;
    dev_t dev = 0;
    ino_t ino = 0;
    timespec mtime = {};
    off_t size = -1;
    int fd = -1;
};

// Returns the copy of `path`, taking it over from `previous` when the file is unchanged, so a
// set change that leaves a module alone (a Tabir toggle) costs a stat, not a copy.
inline SharedText ReuseOrShare(const std::string& path, std::vector<SharedText>& previous) {
    SharedText t;
    t.path = path;
    struct stat st = {};
    if (path.empty() || stat(path.c_str(), &st) != 0) return t;
    t.dev = st.st_dev;
    t.ino = st.st_ino;
    t.mtime = st.st_mtim;
    t.size = st.st_size;
    for (SharedText& old : previous) {
        if (old.fd >= 0 && old.path == path && old.dev == t.dev && old.ino == t.ino &&
            old.size == t.size && old.mtime.tv_sec == t.mtime.tv_sec &&
            old.mtime.tv_nsec == t.mtime.tv_nsec) {
            t.fd = old.fd;
            old.fd = -1;
            return t;
        }
    }
    t.fd = ShareModuleText(path.c_str());
    return t;
}

inline void CloseSharedTexts(std::vector<SharedText>& texts) {
    for (SharedText& t : texts) {
        if (t.fd >= 0) close(t.fd);
    }
    texts.clear();
}

// Zygote's fd table rejects any memfd but ART's, so the shared module fds must be ignored
// by the parent's check. Returns `base` plus every valid shared fd.
inline std::vector<int> IgnoreWithSharedFds(const std::vector<int>& base,
                                            const std::vector<int>& shared) {
    std::vector<int> out(base);
    for (int fd : shared) {
        if (fd >= 0) out.push_back(fd);
    }
    return out;
}

// Closes the inherited shared fds in a child. Every specialized child calls it before app
// code, so a protected app, a child zygote or system_server never holds one.
inline void CloseSharedFds(std::vector<int>& fds) {
    for (int fd : fds) {
        if (fd >= 0) close(fd);
    }
    fds.clear();
}

inline bool ZygiskShouldLoad(uid_t uid, bool is_system_server, bool is_child_zygote,
                             bool enabled, bool safe_bypass, bool circuit_broken,
                             bool has_system_server, const uint32_t* denylist, size_t deny_count,
                             const ZygiskModuleView* modules, size_t module_count,
                             bool* on_denylist) {
    if (on_denylist != nullptr) *on_denylist = false;
    if (is_child_zygote || !enabled || safe_bypass || module_count == 0 || modules == nullptr) {
        return false;
    }
    if (is_system_server) {
        return !circuit_broken && has_system_server;
    }
    const uid_t appid = uid % kUserOffset;
    if (appid < kAppStart || (appid >= kIsolatedStart && appid <= kIsolatedEnd)) {
        return false;
    }
    if (UidInSorted(denylist, deny_count, uid)) {
        if (on_denylist != nullptr) *on_denylist = true;
        return false;
    }
    for (size_t i = 0; i < module_count; ++i) {
        const ZygiskModuleView& m = modules[i];
        if (!m.has_abi) continue;
        if (m.scope_mode == kScopeAllNonDenylist || UidInSorted(m.scope_uids, m.scope_count, uid)) {
            return true;
        }
    }
    return false;
}

inline bool ShouldUnmount(uid_t uid, bool is_system_server, bool is_child_zygote, bool force,
                          bool enrolled, bool default_unmount, const uint32_t* force_uids,
                          size_t force_count, const uint32_t* exempt_uids, size_t exempt_count,
                          bool has_mounts) {
    if (is_system_server || is_child_zygote || !has_mounts) return false;
    if ((uid % kUserOffset) < kAppStart) return false;
    bool should = force || UidInSorted(force_uids, force_count, uid);
    if (!should && !UidInSorted(exempt_uids, exempt_count, uid)) {
        should = default_unmount && !enrolled;
    }
    return should;
}

}  // namespace tabir
}  // namespace yasvara
