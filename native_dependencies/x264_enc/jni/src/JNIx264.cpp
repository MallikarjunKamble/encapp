#include <jni.h>
#include <stdlib.h>
#include "x264.h"
#include "JNIx264.h"

#include <iostream>
#include <fstream>
#include <memory>
using namespace std;

#include <float.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>

X264Encoder* X264Encoder::x264encoder;

X264Encoder& X264Encoder::getInstance() {
    return *x264encoder;
}

X264Encoder::X264Encoder() : encoder(nullptr), nal(nullptr), nnal(0) {}

X264Encoder::~X264Encoder() {
    close();
}

int X264Encoder::init(JNIEnv *env, jobject thisObj, jobject x264ConfigParamsObj,
                       int width, int height, jstring jColorSpace, int bitDepth, jbyteArray headerArray)
{
    assert(x264encoder == NULL);
    x264encoder = new X264Encoder();
    x264_param_t x264Params;
    int size_of_headers;

    if (!x264encoder) {
        return false;
    }

    jclass x264ConfigParamsClass = env->GetObjectClass(x264ConfigParamsObj);

    jfieldID presetFieldID = env->GetFieldID(x264ConfigParamsClass, "preset", "Ljava/lang/String;");
    jfieldID threadsFieldID = env->GetFieldID(x264ConfigParamsClass, "threads", "I");

    jstring presetValueObj = (jstring)env->GetObjectField(x264ConfigParamsObj, presetFieldID);
    const char *presetValue = env->GetStringUTFChars(presetValueObj, NULL);

    if (x264_param_default_preset(&x264Params, presetValue, "zerolatency") < 0) {
        LOGI("Failed to set preset: %s", presetValue);
    }

    jint threadsValue = env->GetIntField(x264ConfigParamsObj, threadsFieldID);

    const char *cColorSpace = env->GetStringUTFChars(jColorSpace, NULL);
    int colorSpace;

    if (strcmp(cColorSpace, "yuv420p") == 0) {
        colorSpace = X264_CSP_I420;
    } else if (strcmp(cColorSpace, "nv12") == 0) {
        colorSpace = X264_CSP_NV12;
    } else if (strcmp(cColorSpace, "nv21") == 0) {
        colorSpace = X264_CSP_NV21;
    } else {
        LOGI("Unsupported color space: %s", cColorSpace);
        env->ReleaseStringUTFChars(jColorSpace, cColorSpace);
        return -1;
    }

    // Mapping to x264 structure members
    x264Params.i_threads = threadsValue;
    x264Params.i_width = width;
    x264Params.i_height = height;
    x264Params.i_csp = colorSpace;
    x264Params.i_bitdepth = bitDepth;

    x264encoder->encoder = x264_encoder_open(&x264Params);
    x264_t *encoder = x264encoder->encoder;
    if(!encoder)
    {
        LOGI("Failed encoder_open");
        return -1;
    }

    x264_encoder_parameters(encoder, &x264Params);
    size_of_headers = x264_encoder_headers(encoder, &(x264encoder->nal), &(x264encoder->nnal));

    x264_nal_t *nal = x264encoder->nal;
    int nnal = x264encoder->nnal;

    int offset = 0;
    if (headerArray != nullptr) {
        jsize headerArraySize = env->GetArrayLength(headerArray);
        jbyte* headerArrayBuffer = env->GetByteArrayElements(headerArray, NULL);

        for (int i = 0; i < nnal; i++) {
            if (nal[i].i_type == NAL_SPS || nal[i].i_type == NAL_PPS || nal[i].i_type == NAL_SEI ||
                nal[i].i_type == NAL_AUD || nal[i].i_type == NAL_FILLER) {
                // Check if there is enough space in the header array
                if (offset + nal[i].i_payload > headerArraySize) {
                    env->ReleaseByteArrayElements(headerArray, headerArrayBuffer, 0);
                    return -1;
                }

                memcpy(headerArrayBuffer + offset, nal[i].p_payload, nal[i].i_payload);
                offset += nal[i].i_payload;
            }
        }
        env->ReleaseByteArrayElements(headerArray, headerArrayBuffer, 0);
    }
    env->ReleaseStringUTFChars(presetValueObj, presetValue);
    return size_of_headers;
}

int X264Encoder::encode(JNIEnv *env, jobject thisObj, jbyteArray yuvBuffer, jbyteArray out_buffer,
                        jint width, jint height, jstring jColorSpace, jobject x264FindIDRObj) {
    if (!encoder) {
        LOGI("Encoder is not initialized for encoding");
        return -1;
    }
    x264_picture_t pic_in = {0};
    x264_picture_t pic_out = {0};

    jclass x264FindIDRClass = env->GetObjectClass(x264FindIDRObj);
    jfieldID checkIDRFieldID = env->GetFieldID(x264FindIDRClass, "checkIDR", "Z");
    jboolean checkIDR;

    jsize yuvBuffer_size = env->GetArrayLength(yuvBuffer);
    jsize out_buffer_size = env->GetArrayLength(out_buffer);

    jbyte* yuvBuffer_data = env->GetByteArrayElements(yuvBuffer, NULL);
    jbyte* out_YuvBuffer = env->GetByteArrayElements(out_buffer, NULL);

    jint ySize, uvSize;
    ySize = width * height;

    x264_picture_init(&pic_in);

    const char *cColorSpace = env->GetStringUTFChars(jColorSpace, NULL);
    int colorSpace;
    int num_planes;

    if (strcmp(cColorSpace, "yuv420p") == 0) {
        colorSpace = X264_CSP_I420;
        num_planes = 3;
        uvSize = width * height / 4;
    } else if (strcmp(cColorSpace, "nv12") == 0) {
        colorSpace = X264_CSP_NV12;
        num_planes = 2;
        uvSize = width * height / 2;
    } else if (strcmp(cColorSpace, "nv21") == 0) {
        colorSpace = X264_CSP_NV21;
        num_planes = 2;
        uvSize = width * height / 2;
    } else {
        LOGI("Unsupported color space: %s", cColorSpace);
        env->ReleaseStringUTFChars(jColorSpace, cColorSpace);
        return -1;
    }

    env->ReleaseStringUTFChars(jColorSpace, cColorSpace);

    pic_in.img.i_csp = colorSpace;
    pic_in.img.i_plane = num_planes;

    x264_picture_alloc(&pic_in, colorSpace, width, height);

    int plane_sizes[3] = {ySize, uvSize, uvSize};
    int plane_offsets[3] = {0, ySize, ySize + uvSize};
    uint8_t* yuv_data_ptr = reinterpret_cast<uint8_t*>(yuvBuffer_data);

    for (int i = 0; i < num_planes; i++) {
        memcpy(pic_in.img.plane[i], yuv_data_ptr + plane_offsets[i], plane_sizes[i]);
    }

    int frame_size = x264_encoder_encode(encoder, &nal, &nnal, &pic_in, &pic_out);

    int total_size = 2;
    if (frame_size > 0) {
        // TODO: Added total_size = 2 for debugging purpose
        for (int i = 0; i < nnal; i++) {
            if (nal[i].i_type == NAL_SLICE_IDR) {
                checkIDR = true;
            } else {
                checkIDR = false;
            }
            total_size += nal[i].i_payload;
        }

        if (out_buffer_size < total_size) {
            out_buffer = env->NewByteArray(total_size);
        }

        env->SetBooleanField(x264FindIDRObj, checkIDRFieldID, checkIDR);
        jbyte *out_buffer_data = env->GetByteArrayElements(out_buffer, NULL);

        int offset = 2;
        for (int i = 0; i < nnal; i++) {
            if (nal[i].i_type == NAL_SPS || nal[i].i_type == NAL_PPS || nal[i].i_type == NAL_SEI ||
            nal[i].i_type == NAL_AUD || nal[i].i_type == NAL_FILLER) {
                continue;
            }
            memcpy(out_buffer_data + offset, nal[i].p_payload, nal[i].i_payload);
            offset += nal[i].i_payload;
        }
        env->ReleaseByteArrayElements(out_buffer, out_buffer_data, 0);
    }

    env->ReleaseByteArrayElements(yuvBuffer, yuvBuffer_data, 0);
    env->ReleaseByteArrayElements(out_buffer, out_YuvBuffer, 0);

    x264_picture_clean(&pic_in);

    return (frame_size > 0) ? total_size: frame_size;
}

void X264Encoder::close()
{
    //x264_t *encoder = x264encoder->encoder;
    if (encoder) {
        x264_encoder_close(encoder);
        encoder = nullptr;
    }
}

extern "C" {

JNIEXPORT jint JNICALL Java_com_facebook_encapp_BufferTranscoder_x264Init(JNIEnv *env, jobject thisObj,
                                                                           jobject x264ConfigParamsObj, int width, int height,
                                                                           jstring jColorSpace, int bitDepth, jbyteArray headerArray) {
    return X264Encoder::init(env, thisObj, x264ConfigParamsObj, width, height, jColorSpace, bitDepth, headerArray);
}

    JNIEXPORT jint JNICALL Java_com_facebook_encapp_BufferTranscoder_x264Encode(JNIEnv *env, jobject thisObj, jbyteArray yuvBuffer,
                                                                                 jbyteArray outBuffer, jint width, jint height,
                                                                                 jstring jColorSpace, jobject x264FindIDRObj) {
        return X264Encoder::getInstance().encode(env, thisObj, yuvBuffer, outBuffer, width, height, jColorSpace, x264FindIDRObj);
}

JNIEXPORT void JNICALL Java_com_facebook_encapp_BufferTranscoder_x264Close(JNIEnv *env, jobject thisObj) {
    X264Encoder::getInstance().close();
}

}
