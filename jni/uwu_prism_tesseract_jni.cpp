/*
 * Copyright (C) 2026 The uwuAOSP Project
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

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <memory>
#include <mutex>
#include <string>

#include <allheaders.h>
#include <tesseract/baseapi.h>

#define LOG_TAG "uwuPrismTesseract"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_tesseract_mutex;
std::unique_ptr<tesseract::TessBaseAPI> g_tesseract;
std::string g_tessdata_path;

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool initialize_tesseract(const std::string & tessdata_path) {
    if (g_tesseract != nullptr && g_tessdata_path == tessdata_path) return true;

    g_tesseract.reset();
    auto api = std::make_unique<tesseract::TessBaseAPI>();
    if (api->Init(
                tessdata_path.c_str(),
                "chi_sim+eng",
                tesseract::OEM_LSTM_ONLY) != 0) {
        LOGE("Tesseract initialization failed");
        return false;
    }
    api->SetPageSegMode(tesseract::PSM_AUTO);
    g_tessdata_path = tessdata_path;
    g_tesseract = std::move(api);
    return true;
}

void throw_illegal_state(JNIEnv * env, const char * message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_uwuaosp_prism_TesseractOcr_nativeRecognize(
        JNIEnv * env,
        jobject,
        jstring image_path,
        jstring tessdata_path) {
    const std::string image = to_string(env, image_path);
    const std::string data = to_string(env, tessdata_path);
    std::lock_guard<std::mutex> lock(g_tesseract_mutex);
    if (image.empty() || data.empty() || !initialize_tesseract(data)) {
        throw_illegal_state(env, "Tesseract is unavailable");
        return nullptr;
    }

    PIX * pix = pixRead(image.c_str());
    if (pix == nullptr) {
        LOGE("Tesseract could not read OCR crop");
        throw_illegal_state(env, "Cannot read OCR crop");
        return nullptr;
    }

    const auto started = std::chrono::steady_clock::now();
    g_tesseract->SetImage(pix);
    g_tesseract->SetSourceResolution(300);
    char * text = g_tesseract->GetUTF8Text();
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started);
    g_tesseract->Clear();
    pixDestroy(&pix);
    LOGI("Tesseract OCR completed in %lld ms", static_cast<long long>(elapsed.count()));

    if (text == nullptr) return env->NewStringUTF("");
    jstring result = env->NewStringUTF(text);
    delete[] text;
    return result;
}
