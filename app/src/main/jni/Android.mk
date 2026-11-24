LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := myclick_touch
LOCAL_SRC_FILES := myclick_touch.cpp
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
