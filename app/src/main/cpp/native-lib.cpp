#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>

#define LOG_TAG "NativeLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_ro_andi_phonebarriers_NativeLib_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ro_andi_phonebarriers_NativeLib_dtwClassifyAndFindMedoidsForPathsAndAnchors(
        JNIEnv* env,
        jobject /* this */,
        jobjectArray points) {

    jsize len = env->GetArrayLength(points);
    LOGD("Processing %d motion points in C++", len);

    if (len == 0) return env->NewStringUTF("{}");

    // Get MotionPoint class and field IDs
    jobject firstPoint = env->GetObjectArrayElement(points, 0);
    jclass cls = env->GetObjectClass(firstPoint);

    jfieldID timestampField = env->GetFieldID(cls, "timestamp", "J");
    jfieldID accelField = env->GetFieldID(cls, "acceleration", "F");
    jfieldID barrierIdField = env->GetFieldID(cls, "barrierId", "Ljava/lang/Integer;");

    for (int i = 0; i < len; i++) {
        jobject point = env->GetObjectArrayElement(points, i);
        jlong timestamp = env->GetLongField(point, timestampField);
        jfloat accel = env->GetFloatField(point, accelField);

        // Accessing Integer barrierId is more complex (needs check for null and intValue() call)
        // For now, logging timestamp and acceleration
        if (i % 10 == 0 || i == len - 1) { // Log every 10th point to avoid flooding
            LOGD("Point %d: timestamp=%lld, accel=%.2f", i, (long long)timestamp, accel);
        }

        env->DeleteLocalRef(point);
    }

    env->DeleteLocalRef(firstPoint);
    env->DeleteLocalRef(cls);

    // return len as string
    std::string result = "{" + std::to_string(len) + "}";
    return env->NewStringUTF(result.c_str());
}
