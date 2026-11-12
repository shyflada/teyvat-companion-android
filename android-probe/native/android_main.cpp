#include "mobile_navigation.hpp"
#include "mobile_map_sync.hpp"

#include <QCoreApplication>
#include <QDir>
#include <QImage>
#include <QJsonDocument>
#include <QJsonObject>
#include <QThread>
#include <atomic>
#include <exception>
#include <jni.h>
#include <memory>
#include <stdexcept>

namespace {
std::atomic<bool> ready{false};

struct Session {
    explicit Session(const QString& directory) : navigation(directory, stop.get_token()) {}

    std::stop_source stop;
    teyvat::MobileNavigation navigation;
};

teyvat::MobileNavigation& navigation(jlong handle) {
    if (!handle) { throw std::runtime_error("Start navigation first."); }
    return reinterpret_cast<Session*>(handle)->navigation;
}

jstring java_string(JNIEnv* env, const QByteArray& bytes) {
    const auto text = QString::fromUtf8(bytes);
    return env->NewString(reinterpret_cast<const jchar*>(text.utf16()), static_cast<jsize>(text.size()));
}

QString qt_string(JNIEnv* env, jstring value) {
    if (!value) { return {}; }
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) { return {}; }
    const auto text = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return text;
}

jstring error_status(JNIEnv* env, const std::exception& error) {
    return java_string(env, QJsonDocument(QJsonObject{
        {"state", "error"}, {"status", QString::fromUtf8(error.what())}}).toJson(QJsonDocument::Compact));
}
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QCoreApplication::setApplicationName(QStringLiteral("TeyvatCompanion"));
    ready.store(true, std::memory_order_release);
    return app.exec();
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_teyvatcompanion_probe_NativeNavigation_create(JNIEnv* env, jclass, jstring directory) {
    try {
        for (int attempt = 0; attempt < 100 && !ready.load(std::memory_order_acquire); ++attempt) { QThread::msleep(50); }
        if (!ready.load(std::memory_order_acquire)) { throw std::runtime_error("Navigation is still starting. Try again."); }
        if (!directory) { throw std::runtime_error("Navigation storage is unavailable."); }
        const auto path = qt_string(env, directory);
        if (env->ExceptionCheck()) { return 0; }
        auto session = std::make_unique<Session>(path);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        if (const auto type = env->FindClass("java/lang/IllegalStateException")) { env->ThrowNew(type, error.what()); }
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_teyvatcompanion_probe_NativeNavigation_observe(JNIEnv* env, jclass, jlong handle, jobject rgba,
        jint width, jint height, jint stride, jboolean world_map, jlong time_ms) {
    try {
        if (!rgba || width < 1 || height < 1 || width > 4096 || height > 4096 || stride < width * 4 || stride > 32768
            || env->GetDirectBufferCapacity(rgba) < static_cast<jlong>(stride) * height) {
            throw std::runtime_error("Invalid capture frame. Restart navigation.");
        }
        const auto* bytes = static_cast<const uchar*>(env->GetDirectBufferAddress(rgba));
        if (!bytes) { throw std::runtime_error("Capture frame is unavailable."); }
        const QImage frame(bytes, width, height, stride, QImage::Format_RGBA8888);
        return java_string(env, navigation(handle).observe(frame, world_map == JNI_TRUE, time_ms));
    } catch (const std::exception& error) { return error_status(env, error); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_teyvatcompanion_probe_NativeNavigation_targetAction(JNIEnv* env, jclass, jlong handle, jint action, jstring expected) {
    try {
        const auto id = qt_string(env, expected);
        if (env->ExceptionCheck()) { return nullptr; }
        return java_string(env, navigation(handle).targetAction(action, id));
    }
    catch (const std::exception& error) { return error_status(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_app_teyvatcompanion_probe_NativeNavigation_configure(JNIEnv* env, jclass, jlong handle, jint area, jint near, jboolean automatic) {
    try { navigation(handle).configure(area, near, automatic == JNI_TRUE); }
    catch (const std::exception& error) {
        if (const auto type = env->FindClass("java/lang/IllegalStateException")) { env->ThrowNew(type, error.what()); }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_teyvatcompanion_probe_NativeNavigation_cancel(JNIEnv*, jclass, jlong handle) {
    if (handle) { reinterpret_cast<Session*>(handle)->stop.request_stop(); }
}

extern "C" JNIEXPORT void JNICALL
Java_app_teyvatcompanion_probe_NativeNavigation_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_teyvatcompanion_probe_HoYoLabActivity_openSync(JNIEnv* env, jclass, jstring directory) {
    try {
        if (!ready.load(std::memory_order_acquire)) { throw std::runtime_error("Navigation is still starting. Try again."); }
        const auto path = qt_string(env, directory);
        if (env->ExceptionCheck()) { return 0; }
        if (!QDir::isAbsolutePath(path)) { throw std::runtime_error("Local storage is unavailable."); }
        return reinterpret_cast<jlong>(new teyvat::MobileMapSync(path));
    } catch (const std::exception& error) {
        if (const auto type = env->FindClass("java/lang/IllegalStateException")) { env->ThrowNew(type, error.what()); }
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_teyvatcompanion_probe_HoYoLabActivity_syncRequest(JNIEnv* env, jclass, jlong handle) {
    try { return java_string(env, reinterpret_cast<teyvat::MobileMapSync*>(handle)->request()); }
    catch (const std::exception& error) { return error_status(env, error); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_teyvatcompanion_probe_HoYoLabActivity_syncResponse(JNIEnv* env, jclass, jlong handle, jstring response) {
    try {
        const auto text = qt_string(env, response);
        if (env->ExceptionCheck()) { return nullptr; }
        return java_string(env, reinterpret_cast<teyvat::MobileMapSync*>(handle)->accept(text.toUtf8()));
    } catch (const std::exception& error) { return error_status(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_app_teyvatcompanion_probe_HoYoLabActivity_closeSync(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<teyvat::MobileMapSync*>(handle);
}
