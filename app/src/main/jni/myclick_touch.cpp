#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <thread>

#include <jni.h>

namespace {

constexpr char kTag[] = "MYclickNativeTouch";
constexpr int kMaxSlots = 10;

/*
 * 触摸可视化按“按下就产生一次特效”工作，不再区分点击和滑动。
 *
 * ImGui 的 Android 后端（imgui_impl_android.cpp）只把触摸当成
 * DOWN / MOVE / UP / CANCEL 的连续状态：按下只更新位置，移动继续更新，
 * 抬起才结束一次交互。这里沿用同样的语义：tracking ID 按下时开始一次
 * 触摸，坐标就绪后立刻上报一次 onTouchStart，之后不再等待抬手。
 */
struct Slot {
    bool active = false;
    bool startNotified = false;
    bool freshX = false;
    bool freshY = false;
    int trackingId = -1;
    int x = -1;
    int y = -1;
    int startX = -1;
    int startY = -1;
};

struct Monitor {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;
    jmethodID onStart = nullptr;
    int fd = -1;
    std::atomic<bool> stopping{false};
    std::atomic<bool> running{false};
    std::thread worker;
};

void notifyStart(Monitor* monitor, JNIEnv* env, int x, int y) {
    env->CallVoidMethod(
        monitor->listener,
        monitor->onStart,
        static_cast<jint>(x),
        static_cast<jint>(y)
    );
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/* 驱动可能先发 tracking ID 再补坐标；等两个轴都有值后再上报按下点。 */
void maybeNotifyStart(Monitor* monitor, Slot& slot, JNIEnv* env) {
    if (!slot.active || slot.startNotified || slot.startX < 0 || slot.startY < 0) return;
    slot.startNotified = true;
    notifyStart(monitor, env, slot.startX, slot.startY);
}

void beginTouch(Monitor* monitor, Slot& slot, int trackingId, JNIEnv* env) {
    slot.active = true;
    slot.startNotified = false;
    slot.trackingId = trackingId;

    // 按下前的坐标事件可能来自同一帧；有新鲜坐标时用它作为起点。
    // 没有时等坐标事件到达后再上报，避免拿上一次触摸的旧坐标。
    slot.startX = slot.freshX ? slot.x : -1;
    slot.startY = slot.freshY ? slot.y : -1;
    slot.freshX = false;
    slot.freshY = false;

    maybeNotifyStart(monitor, slot, env);
}

void endTouch(Slot& slot) {
    if (!slot.active) return;

    slot.active = false;
    slot.startNotified = false;
    slot.trackingId = -1;
    slot.startX = -1;
    slot.startY = -1;
    slot.freshX = false;
    slot.freshY = false;
}

void updateX(Monitor* monitor, Slot& slot, int value, JNIEnv* env) {
    slot.x = value;
    if (!slot.active) {
        slot.freshX = true;
        return;
    }
    if (slot.startX < 0) slot.startX = value;
    maybeNotifyStart(monitor, slot, env);
}

void updateY(Monitor* monitor, Slot& slot, int value, JNIEnv* env) {
    slot.y = value;
    if (!slot.active) {
        slot.freshY = true;
        return;
    }
    if (slot.startY < 0) slot.startY = value;
    maybeNotifyStart(monitor, slot, env);
}

void resetSlot(Slot& slot) {
    slot.active = false;
    slot.startNotified = false;
    slot.freshX = false;
    slot.freshY = false;
    slot.trackingId = -1;
    slot.startX = -1;
    slot.startY = -1;
}

void readLoop(Monitor* monitor) {
    pthread_setname_np(pthread_self(), "fx-touch-evdev");
    JNIEnv* env = nullptr;
    bool attached = false;
    if (monitor->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (monitor->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            monitor->running = false;
            return;
        }
        attached = true;
    }

    Slot slots[kMaxSlots];
    int currentSlot = 0;
    __android_log_print(ANDROID_LOG_INFO, kTag, "native evdev monitor started");

    while (!monitor->stopping.load()) {
        pollfd descriptor{monitor->fd, POLLIN, 0};
        const int ready = poll(&descriptor, 1, 250);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ready == 0) continue;
        if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) break;

        input_event event{};
        const ssize_t count = read(monitor->fd, &event, sizeof(event));
        if (count < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }
        if (count != static_cast<ssize_t>(sizeof(event))) continue;

        if (event.type == EV_ABS) {
            if (event.code == ABS_MT_SLOT) {
                currentSlot = event.value;
                if (currentSlot < 0 || currentSlot >= kMaxSlots) currentSlot = 0;
                continue;
            }

            Slot& slot = slots[currentSlot];
            switch (event.code) {
                case ABS_MT_POSITION_X:
                case ABS_X:
                    updateX(monitor, slot, event.value, env);
                    break;
                case ABS_MT_POSITION_Y:
                case ABS_Y:
                    updateY(monitor, slot, event.value, env);
                    break;
                case ABS_MT_TRACKING_ID:
                    if (event.value >= 0) {
                        if (!slot.active) {
                            beginTouch(monitor, slot, event.value, env);
                        } else if (slot.trackingId < 0) {
                            // BTN_TOUCH 可能先于 tracking ID 到达，补齐 ID 即可。
                            slot.trackingId = event.value;
                        } else if (slot.trackingId != event.value) {
                            // 少数驱动不先发 -1 就直接复用 slot；结束旧触点再开始新触点。
                            endTouch(slot);
                            beginTouch(monitor, slot, event.value, env);
                        }
                    } else {
                        endTouch(slot);
                    }
                    break;
                default:
                    break;
            }
        } else if (event.type == EV_KEY && event.code == BTN_TOUCH) {
            Slot& slot = slots[currentSlot];
            if (event.value != 0) {
                if (!slot.active) beginTouch(monitor, slot, -1, env);
            } else if (slot.active) {
                endTouch(slot);
            } else {
                // 某些驱动只在最后一帧发 BTN_TOUCH UP；把仍活动的触点全部收尾。
                for (Slot& activeSlot : slots) {
                    if (activeSlot.active) endTouch(activeSlot);
                }
            }
        } else if (event.type == EV_SYN && event.code == SYN_REPORT) {
            // 有的驱动不会在 tracking ID 之后补全两轴；在本帧结束前兜底上报，
            // 仍然只上报一次，也仍然不判断滑动。
            for (Slot& slot : slots) {
                if (slot.active && !slot.startNotified &&
                    slot.x >= 0 && slot.y >= 0) {
                    if (slot.startX < 0) slot.startX = slot.x;
                    if (slot.startY < 0) slot.startY = slot.y;
                    maybeNotifyStart(monitor, slot, env);
                }
                slot.freshX = false;
                slot.freshY = false;
            }
        } else if (event.type == EV_SYN && event.code == SYN_DROPPED) {
            for (Slot& slot : slots) resetSlot(slot);
            currentSlot = 0;
        }
    }

    monitor->running = false;
    if (attached) monitor->vm->DetachCurrentThread();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_myclick_shell_NativeTouchMonitor_nativeStart(
    JNIEnv* env, jclass, jstring path, jobject listener
) {
    if (path == nullptr || listener == nullptr) return 0;
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    if (pathChars == nullptr) return 0;
    const int fd = open(pathChars, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "open %s failed errno=%d", pathChars, errno);
        env->ReleaseStringUTFChars(path, pathChars);
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "opened %s", pathChars);
    fprintf(stderr, "MYclickNativeTouch: opened %s\n", pathChars);
    env->ReleaseStringUTFChars(path, pathChars);

    auto* monitor = new Monitor();
    if (env->GetJavaVM(&monitor->vm) != JNI_OK) {
        close(fd);
        delete monitor;
        return 0;
    }
    monitor->fd = fd;
    monitor->listener = env->NewGlobalRef(listener);
    jclass listenerClass = env->GetObjectClass(listener);
    monitor->onStart = env->GetMethodID(listenerClass, "onTouchStart", "(II)V");
    env->DeleteLocalRef(listenerClass);
    if (monitor->listener == nullptr || monitor->onStart == nullptr) {
        if (monitor->listener != nullptr) env->DeleteGlobalRef(monitor->listener);
        close(fd);
        delete monitor;
        return 0;
    }

    monitor->running = true;
    monitor->worker = std::thread(readLoop, monitor);
    __android_log_print(ANDROID_LOG_INFO, kTag, "native evdev monitor available");
    return reinterpret_cast<jlong>(monitor);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_myclick_shell_NativeTouchMonitor_nativeRunning(JNIEnv*, jclass, jlong handle) {
    auto* monitor = reinterpret_cast<Monitor*>(handle);
    return monitor != nullptr && monitor->running.load() && !monitor->stopping.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_myclick_shell_NativeTouchMonitor_nativeStop(JNIEnv* env, jclass, jlong handle) {
    auto* monitor = reinterpret_cast<Monitor*>(handle);
    if (monitor == nullptr) return;
    monitor->stopping = true;
    if (monitor->worker.joinable()) monitor->worker.join();
    if (monitor->fd >= 0) close(monitor->fd);
    if (monitor->listener != nullptr) env->DeleteGlobalRef(monitor->listener);
    delete monitor;
}
