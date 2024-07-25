#include <jni.h>
#include <android/log.h>
#include "x264.h"

#ifndef JNIx264
#define JNIx264

class X264Encoder {
public:
    static X264Encoder& getInstance();

    static int init(JNIEnv *env, jobject thisObj,
                    jobject x264ConfigParamsObj, int width, int height,
                    jstring jColorSpace, int bitDepth, jbyteArray headerArray);

    int encode(JNIEnv *env, jobject thisObj, jbyteArray yuvBuffer, jbyteArray out_buffer,
               jint width, jint height, jstring jColorSpace, jobject x264FindIDRObj);

    void close();

    x264_t *encoder;
    x264_nal_t *nal;
    int nnal;

private:
    X264Encoder();
    ~X264Encoder();

    X264Encoder(const X264Encoder&) = delete;
    X264Encoder& operator=(const X264Encoder&) = delete;

    static X264Encoder* x264encoder;
};

#ifdef __cplusplus
extern "C" {
#endif

#define LOG_TAG "x264-encoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

    JNIEXPORT jint Java_com_facebook_encapp_BufferTranscoder_x264Init(JNIEnv *env, jobject thisObj,
                                                                       jobject x264ConfigParamsObj, int width, int height,
                                                                       jstring jColorSpace, int bitDepth, jbyteArray headerArray);

    JNIEXPORT jint Java_com_facebook_encapp_BufferTranscoder_x264Encode(JNIEnv *env, jobject thisObj, jbyteArray yuvBuffer,
                                                                         jbyteArray out_buffer, jint width, jint height,
                                                                         jstring jColorSpace, jobject x264FindIDRObj);

    JNIEXPORT void Java_com_facebook_encapp_BufferTranscoder_x264Close(JNIEnv *env, jobject thisObj);

#ifdef __cplusplus
}
#endif
#endif