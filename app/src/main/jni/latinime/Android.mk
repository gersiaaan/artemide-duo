# Artemide Duo "Plus": the AOSP LatinIME suggestion engine (via HeliBoard, Apache 2.0)
# for the Duo keyboard. Only built when DUO_KEYBOARD=plus (the duoPlus build type).
ifeq ($(DUO_KEYBOARD),plus)

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LATIN_IME_SRC_DIR := src
include $(LOCAL_PATH)/NativeFileList.mk

LOCAL_MODULE := jni_latinime
LOCAL_C_INCLUDES += $(LOCAL_PATH)/$(LATIN_IME_SRC_DIR)
LOCAL_SRC_FILES := \
    $(LATIN_IME_JNI_SRC_FILES) \
    $(addprefix $(LATIN_IME_SRC_DIR)/, $(LATIN_IME_CORE_SRC_FILES))
LOCAL_CFLAGS += -Wno-unused-parameter -Wno-unused-function -w -O2
LOCAL_LDLIBS += -llog -ldl
LOCAL_LDFLAGS += -Wl,--build-id=none

include $(BUILD_SHARED_LIBRARY)

include $(LOCAL_PATH)/CleanupNativeFileList.mk

endif
