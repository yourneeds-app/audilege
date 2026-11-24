// Adapted for Audilege from Sonora 0.6.1 build 34. See airplay-licenses.
#include <jni.h>
#include <atomic>
#include <mutex>
#include <thread>
#include <string>
#include <algorithm>
#include <utility>
#include "raop_sender.h"
#include "raop_loop.h"
#include "ring_buffer.h"
#include <memory>
#include <arpa/inet.h>
extern "C" {
#include "ptp/ap2_ptp.h"
}

using namespace fxchain;
namespace {
std::mutex lifecycle, data;
std::thread worker;
std::atomic<bool> stop{false}, connected{false}, failed{false};
std::atomic<bool> playing{false};
std::atomic<int> rate{44100}, volume{-1};
std::atomic<int> receiverDelayMs{1500};
// ExoPlayer primes its AudioTrack while paused, before completing a seek.
// The remote queue must accommodate that preroll without requiring playback.
RingBuffer<int16_t> ring(524288);
std::atomic<uint64_t> flushRequest{0}, flushed{0};
std::atomic<size_t> staged{0};
std::string title, artist;
bool metadataChanged = false;
std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string result(chars); env->ReleaseStringUTFChars(s, chars); return result;
}
void endSession() {
    stop = true;
    if (worker.joinable()) worker.join();
    connected = false;
    playing = false;
    // Called only once the consumer has stopped; producer holds lifecycle.
    ring.reset();
}
}
#define JNI_METHOD(name) Java_app_yourneeds_foliocue_AirPlayNative_##name
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeConnect)(JNIEnv* env, jobject,
        jstring hostValue, jint port, jstring nameValue, jstring identityValue) {
    std::lock_guard lock(lifecycle); endSession();
    failed = false; flushed = 0; flushRequest = 0; staged = 0; rate = 44100;
    auto host = str(env, hostValue), name = str(env, nameValue), identity = str(env, identityValue);
    { std::lock_guard state(data); title.clear(); artist.clear(); metadataChanged = false; }
    stop = false; volume = -1; receiverDelayMs = 1500;
    worker = std::thread([host, port, name, identity] {
      try {
        RaopLoop loop;
        std::unique_ptr<ap2_ptp_ctx, decltype(&ap2_ptp_destroy)> ptp(nullptr, ap2_ptp_destroy);
        RaopEvents events;
        events.startPtpClock = [&](const std::string& localIp, const std::string& receiverIp) -> uint64_t {
            // EUI-64 derived from this installation's existing random device ID.
            std::string hex;
            for (char c : identity) if (c != ':') hex += c;
            if (hex.size() != 12) return 0;
            const uint64_t mac = std::stoull(hex, nullptr, 16);
            const uint64_t clockId = ((mac >> 24) << 40) | (0xfffeULL << 24) | (mac & 0xffffffULL);
            ptp.reset(ap2_ptp_create());
            if (!ptp) return 0;
            ap2_ptp_set_clock_id(ptp.get(), clockId);
            const char* peers[] = {receiverIp.c_str()};
            ap2_ptp_set_peers(ptp.get(), peers, 1);
            in_addr bindAddress{};
            if (inet_pton(AF_INET, localIp.c_str(), &bindAddress) != 1 ||
                !ap2_ptp_engine_start(ptp.get(), bindAddress, receiverIp.c_str())) {
                ptp.reset(); return 0;
            }
            receiverDelayMs = 2000;
            return clockId;
        };
        events.launched = [](bool ok, const std::string&) {
            connected = ok;
            if (!ok) { failed = true; stop = true; }
        };
        events.closed = [] { connected = false; failed = true; stop = true; };
        events.pinRequired = [](const std::string&) { failed = true; stop = true; };
        RaopSender sender(loop, events);
        sender.setStrictReceiverAuth(true);
        sender.setRealtimePcm(false);
        sender.setIdentity({"Audilege", identity, "iPhone14,3"});
        sender.setAuth(RaopDeviceInfo::Auth::HapTransient,
                       true, host, "", "");
        sender.attachRing(&ring);
        sender.setInputFormat(rate);
        sender.start(host, static_cast<uint16_t>(port), name);
        sender.setVolume(25); // Before the handshake can release any audio.
        int lastRate = rate;
        while (!stop) {
            // ExoPlayer may decode ahead while paused; never transmit that PCM
            // until the player's transport is actually running.
            sender.attachRing(playing ? &ring : nullptr);
            const auto request = flushRequest.load();
            if (request != flushed.load()) {
                // Consumer-only discard; no reset while producer is writing.
                ring.discard();
                sender.discardQueuedAudio();
                staged = 0;
                flushed = request;
            }
            int newRate = rate;
            if (newRate != lastRate) { sender.setInputFormat(newRate); lastRate = newRate; }
            int newVolume = volume.exchange(-1);
            if (newVolume >= 0) sender.setVolume(newVolume);
            std::string nextTitle, nextArtist;
            bool updateMetadata;
            {
                std::lock_guard lock(data);
                updateMetadata = metadataChanged;
                if (updateMetadata) { nextTitle = title; nextArtist = artist; metadataChanged = false; }
            }
            if (updateMetadata) sender.setNowPlaying(nextTitle, nextArtist, "");
            loop.pump(sender, std::chrono::milliseconds(8));
            staged = sender.queuedSourceFrames();
        }
        sender.stop();
      } catch (const std::exception&) {
        failed = true;
      }
      connected = false;
    });
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeDisconnect)(JNIEnv*, jobject) {
    std::lock_guard lock(lifecycle); endSession();
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeConnected)(JNIEnv*, jobject) { return connected; }
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeFailed)(JNIEnv*, jobject) { return failed; }
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeReceiverDelayMillis)(JNIEnv*, jobject) { return receiverDelayMs.load(); }
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(nativeBufferedMillis)(JNIEnv*, jobject) {
    if (!connected || flushed != flushRequest.load()) return 0;
    const size_t samples = std::min(ring.availableRead(), ring.capacity());
    return static_cast<jlong>(samples / 2 + staged.load()) * 1000 / rate.load();
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeVolume)(JNIEnv*, jobject, jint value) { volume = value; }
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeFlush)(JNIEnv*, jobject) { ++flushRequest; }
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativePlaying)(JNIEnv*, jobject, jboolean value) { playing = value; }
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeMetadata)(JNIEnv* env, jobject, jstring t, jstring a) {
    std::lock_guard lock(data); title = str(env, t); artist = str(env, a); metadataChanged = true;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeWrite)(JNIEnv* env, jobject, jshortArray samples, jint sampleRate) {
    std::unique_lock lock(lifecycle, std::try_to_lock);
    if (!lock || !connected || flushed != flushRequest.load() || sampleRate <= 0) return false;
    rate = sampleRate;
    auto count = env->GetArrayLength(samples);
    auto* pcm = env->GetShortArrayElements(samples, nullptr);
    if (!pcm) return false;
    bool accepted = ring.tryPush(std::span<const int16_t>(pcm, count));
    env->ReleaseShortArrayElements(samples, pcm, JNI_ABORT);
    return accepted;
}
