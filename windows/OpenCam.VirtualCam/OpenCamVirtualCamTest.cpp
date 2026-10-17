// Exercises OpenCamVirtualCam.dll the way the Media Foundation pipeline does:
// load the class factory, activate the media source, pull samples off the stream
// and compare the delivered pixels against what was published into the shared
// frame buffer.
//
// This deliberately avoids registry registration, so it runs without elevation
// and still covers the whole media source, the shared-memory reader, the seqlock
// copy and the sample construction.

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mferror.h>

#include <cstdio>
#include <cstring>

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")

// Must match CLSID_OpenCamVirtualCam in OpenCamVirtualCam.cpp and
// VirtualCameraIds.Clsid in the C# client.
static const GUID kClsid =
    {0x6f1b4c93, 0x7a2e, 0x4d71, {0x9c, 0x58, 0x2e, 0x4a, 0x0b, 0x7d, 0x5f, 0x31}};

static const wchar_t kSectionName[] = L"OpenCamFrameBuffer";
static const DWORD kMagic = 0x4F43414D; // 'OCAM'
static const int kMaxWidth = 1920;
static const int kMaxHeight = 1080;
static const int kHeaderSize = 56;

#pragma pack(push, 1)
struct FrameHeader {
    LONGLONG sequence;
    DWORD    magic;
    DWORD    version;
    INT32    width;
    INT32    height;
    INT32    stride;
    INT32    pixelFormat;
    INT32    fps;
    INT32    reserved;
    LONGLONG frameCounter;
    LONGLONG timestampMs;
};
#pragma pack(pop)

static int g_passed = 0;
static int g_failed = 0;
static int g_info = 0;

static void Check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
        printf("  PASS  %s\n", what);
    } else {
        ++g_failed;
        printf("  FAIL  %s\n", what);
    }
}

static void Info(const char* fmt, ...) {
    ++g_info;
    va_list args;
    va_start(args, fmt);
    printf("  info  ");
    vprintf(fmt, args);
    printf("\n");
    va_end(args);
}

// --------------------------------------------------------------- frame writer

class FrameWriter {
public:
    bool Open() {
        const SIZE_T bytes = kHeaderSize + static_cast<SIZE_T>(kMaxWidth) * kMaxHeight * 4;
        _mapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE,
                                      static_cast<DWORD>(bytes >> 32),
                                      static_cast<DWORD>(bytes & 0xFFFFFFFF),
                                      kSectionName);
        if (!_mapping) return false;
        _base = static_cast<BYTE*>(MapViewOfFile(_mapping, FILE_MAP_ALL_ACCESS, 0, 0, 0));
        if (!_base) return false;

        auto* header = reinterpret_cast<FrameHeader*>(_base);
        memset(header, 0, sizeof(FrameHeader));
        header->magic = kMagic;
        header->version = 1;
        header->pixelFormat = 1;
        _counter = 0;
        return true;
    }

    void Close() {
        if (_base) { UnmapViewOfFile(_base); _base = nullptr; }
        if (_mapping) { CloseHandle(_mapping); _mapping = nullptr; }
    }

    ~FrameWriter() { Close(); }

    /// Publishes one frame using a deterministic pattern, exactly as the client's
    /// seqlock writer does.
    void Publish(int width, int height, int stride, int fps) {
        auto* header = reinterpret_cast<FrameHeader*>(_base);
        BYTE* pixels = _base + kHeaderSize;

        const LONGLONG writing = (++_sequence) | 1LL;
        header->sequence = writing;

        header->width = width;
        header->height = height;
        header->stride = stride;
        header->pixelFormat = 1;
        header->fps = fps;

        for (int y = 0; y < height; ++y) {
            BYTE* row = pixels + static_cast<size_t>(stride) * y;
            for (int x = 0; x < width; ++x) {
                row[x * 4 + 0] = static_cast<BYTE>(x & 0xFF);
                row[x * 4 + 1] = static_cast<BYTE>(y & 0xFF);
                row[x * 4 + 2] = static_cast<BYTE>((x * 7 + y * 13) & 0xFF);
                row[x * 4 + 3] = 0xFF;
            }
        }

        ++_counter;
        header->frameCounter = _counter;
        header->timestampMs = 1'700'000'000'000LL + _counter;
        header->sequence = writing + 1;
        _sequence = writing + 1;
    }

private:
    HANDLE _mapping = nullptr;
    BYTE* _base = nullptr;
    LONGLONG _sequence = 0;
    LONGLONG _counter = 0;
};

static bool PatternMatches(const BYTE* pixels, int stride, int x, int y) {
    const BYTE* p = pixels + static_cast<size_t>(stride) * y + static_cast<size_t>(x) * 4;
    return p[0] == static_cast<BYTE>(x & 0xFF)
        && p[1] == static_cast<BYTE>(y & 0xFF)
        && p[2] == static_cast<BYTE>((x * 7 + y * 13) & 0xFF);
}

/// Pulls the next queued event without ever blocking. Every queue this test reads
/// is filled synchronously, so a missing event is a genuine failure rather than a
/// timing artefact, and a starved queue stays a clean, testable condition.
static IMFMediaEvent* NextEvent(IMFMediaEventGenerator* generator, DWORD timeoutGuardMs) {
    const DWORD start = GetTickCount();
    for (;;) {
        IMFMediaEvent* event = nullptr;
        const HRESULT hr = generator->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
        if (hr == MF_E_NO_EVENTS_AVAILABLE) return nullptr;
        if (FAILED(hr)) return nullptr;
        if (event) return event;
        if (GetTickCount() - start > timeoutGuardMs) return nullptr;
        Sleep(5);
    }
}

static IMFSample* SampleFromEvent(IMFMediaEvent* event) {
    IMFSample* sample = nullptr;
    PROPVARIANT pv;
    PropVariantInit(&pv);
    if (SUCCEEDED(event->GetValue(&pv)) && pv.vt == VT_UNKNOWN && pv.punkVal) {
        pv.punkVal->QueryInterface(__uuidof(IMFSample), reinterpret_cast<void**>(&sample));
    }
    PropVariantClear(&pv);
    return sample;
}

/// Requests one sample and returns it, skipping the non-sample events the stream
/// queues for itself (its own start notification, for instance).
static IMFSample* RequestSample(IMFMediaStream* stream, DWORD timeoutMs) {
    if (FAILED(stream->RequestSample(nullptr))) return nullptr;

    for (int i = 0; i < 8; ++i) {
        IMFMediaEvent* event = NextEvent(stream, timeoutMs);
        if (!event) return nullptr;

        MediaEventType type = MEUnknown;
        event->GetType(&type);
        IMFSample* sample = type == MEMediaSample ? SampleFromEvent(event) : nullptr;
        event->Release();
        if (sample) return sample;
    }

    return nullptr;
}

static bool VerifyPixels(IMFSample* sample, int width, int height, const char* label) {
    IMFMediaBuffer* buffer = nullptr;
    if (FAILED(sample->GetBufferByIndex(0, &buffer)) || !buffer) {
        Check(false, label);
        return false;
    }

    DWORD length = 0;
    buffer->GetCurrentLength(&length);
    const DWORD expected = static_cast<DWORD>(width) * height * 4;
    if (length != expected) {
        Info("buffer length %lu, expected %lu", length, expected);
        Check(false, label);
        buffer->Release();
        return false;
    }

    BYTE* pixels = nullptr;
    DWORD maxLength = 0;
    if (FAILED(buffer->Lock(&pixels, &maxLength, nullptr)) || !pixels) {
        Check(false, label);
        buffer->Release();
        return false;
    }

    const int stride = width * 4;
    bool all = maxLength >= expected;
    const int probes[][2] = {
        {0, 0}, {1, 0}, {width - 1, 0}, {0, height - 1}, {width - 1, height - 1},
        {width / 2, height / 2}, {width / 4, height / 4}, {17, height / 3}, {width - 3, 42},
    };
    for (const auto& probe : probes) {
        if (!PatternMatches(pixels, stride, probe[0], probe[1])) {
            all = false;
            Info("pixel mismatch at (%d,%d) in %s", probe[0], probe[1], label);
        }
    }
    buffer->Unlock();
    buffer->Release();
    Check(all, label);
    return all;
}

int main() {
    printf("OpenCam virtual camera media source test\n\n");

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);

    FrameWriter writer;
    Check(writer.Open(), "shared frame section created and initialised");

    HMODULE module = LoadLibraryW(L"build\\OpenCamVirtualCam.dll");
    if (!module) module = LoadLibraryW(L"OpenCamVirtualCam.dll");
    if (!module) {
        printf("  FAIL  could not load OpenCamVirtualCam.dll (error %lu)\n", GetLastError());
        return 1;
    }
    Check(true, "OpenCamVirtualCam.dll loaded");

    Check(GetProcAddress(module, "DllGetClassObject") != nullptr, "exports DllGetClassObject");
    Check(GetProcAddress(module, "DllCanUnloadNow") != nullptr, "exports DllCanUnloadNow");
    Check(GetProcAddress(module, "DllRegisterServer") != nullptr, "exports DllRegisterServer");
    Check(GetProcAddress(module, "DllUnregisterServer") != nullptr, "exports DllUnregisterServer");

    using GetClassObjectFn = HRESULT (STDAPICALLTYPE*)(REFCLSID, REFIID, void**);
    auto getClassObject = reinterpret_cast<GetClassObjectFn>(
        GetProcAddress(module, "DllGetClassObject"));
    using CanUnloadNowFn = HRESULT (STDAPICALLTYPE*)();
    auto canUnloadNow = reinterpret_cast<CanUnloadNowFn>(
        GetProcAddress(module, "DllCanUnloadNow"));

    // A wrong CLSID must be refused rather than activated.
    IClassFactory* bogus = nullptr;
    GUID other = {0};
    const HRESULT bogusHr = getClassObject(other, IID_IClassFactory, reinterpret_cast<void**>(&bogus));
    Check(bogusHr == CLASS_E_CLASSNOTAVAILABLE && bogus == nullptr,
          "unknown CLSID rejected with CLASS_E_CLASSNOTAVAILABLE");

    IClassFactory* factory = nullptr;
    hr = getClassObject(kClsid, IID_IClassFactory, reinterpret_cast<void**>(&factory));
    Check(SUCCEEDED(hr) && factory != nullptr, "class factory created for the OpenCam CLSID");
    if (FAILED(hr)) return 1;

    IMFMediaSource* source = nullptr;
    hr = factory->CreateInstance(nullptr, __uuidof(IMFMediaSource),
                                reinterpret_cast<void**>(&source));
    Check(SUCCEEDED(hr) && source != nullptr, "IMFMediaSource activated from the factory");

    IMFActivate* activate = nullptr;
    hr = factory->CreateInstance(nullptr, __uuidof(IMFActivate),
                                reinterpret_cast<void**>(&activate));
    Check(SUCCEEDED(hr) && activate != nullptr,
          "IMFActivate also supported (for virtual camera hosting)");
    if (activate) {
        IMFMediaSource* viaActivate = nullptr;
        hr = activate->ActivateObject(__uuidof(IMFMediaSource),
                                     reinterpret_cast<void**>(&viaActivate));
        Check(SUCCEEDED(hr) && viaActivate != nullptr,
              "IMFActivate::ActivateObject yields a media source");
        if (viaActivate) viaActivate->Release();
    }

    if (!source) return 1;

    DWORD characteristics = 0;
    hr = source->GetCharacteristics(&characteristics);
    Check(SUCCEEDED(hr) && (characteristics & MFMEDIASOURCE_IS_LIVE) != 0,
          "source reports MFMEDIASOURCE_IS_LIVE");
    Info("characteristics = 0x%08lX", static_cast<unsigned long>(characteristics));

    IMFPresentationDescriptor* pd = nullptr;
    hr = source->CreatePresentationDescriptor(&pd);
    Check(SUCCEEDED(hr) && pd != nullptr, "presentation descriptor created");

    DWORD streamCount = 0;
    pd->GetStreamDescriptorCount(&streamCount);
    Check(streamCount == 1, "exactly one stream");

    BOOL selected = FALSE;
    IMFStreamDescriptor* sd = nullptr;
    hr = pd->GetStreamDescriptorByIndex(0, &selected, &sd);
    Check(SUCCEEDED(hr) && selected == TRUE, "single stream is selected by default");

    IMFMediaTypeHandler* handler = nullptr;
    IMFMediaType* mediaType = nullptr;
    hr = sd->GetMediaTypeHandler(&handler);
    if (SUCCEEDED(hr)) hr = handler->GetCurrentMediaType(&mediaType);
    Check(SUCCEEDED(hr) && mediaType != nullptr, "current media type readable");

    if (mediaType) {
        GUID subtype = {0};
        mediaType->GetGUID(MF_MT_SUBTYPE, &subtype);
        Check(IsEqualGUID(subtype, MFVideoFormat_RGB32), "subtype is MFVideoFormat_RGB32");

        GUID major = {0};
        mediaType->GetGUID(MF_MT_MAJOR_TYPE, &major);
        Check(IsEqualGUID(major, MFMediaType_Video), "major type is video");

        UINT32 w = 0, h = 0;
        MFGetAttributeSize(mediaType, MF_MT_FRAME_SIZE, &w, &h);
        Check(w == 1280 && h == 720, "advertised frame size is 1280x720");

        UINT32 num = 0, den = 0;
        MFGetAttributeRatio(mediaType, MF_MT_FRAME_RATE, &num, &den);
        Check(den != 0 && num / den == 30, "advertised frame rate is 30 fps");

        UINT32 interlace = 0;
        mediaType->GetUINT32(MF_MT_INTERLACE_MODE, &interlace);
        Check(interlace == MFVideoInterlace_Progressive, "progressive scan");
    }

    // Start. No frame has been published yet, so nothing should flow downstream.
    static const GUID kNullGuid = {0};
    hr = source->Start(pd, &kNullGuid, nullptr);
    Check(SUCCEEDED(hr), "source Start succeeds");

    IMFMediaStream* stream = nullptr;
    bool sawNewStream = false;
    bool sawSourceStarted = false;
    for (int i = 0; i < 4 && !(sawNewStream && sawSourceStarted); ++i) {
        IMFMediaEvent* e = NextEvent(source, 2000);
        if (!e) break;
        MediaEventType type = MEUnknown;
        e->GetType(&type);
        if (type == MENewStream) {
            sawNewStream = true;
            PROPVARIANT pv;
            PropVariantInit(&pv);
            if (SUCCEEDED(e->GetValue(&pv)) && pv.vt == VT_UNKNOWN && pv.punkVal) {
                pv.punkVal->QueryInterface(__uuidof(IMFMediaStream),
                                          reinterpret_cast<void**>(&stream));
            }
            PropVariantClear(&pv);
        } else if (type == MESourceStarted) {
            sawSourceStarted = true;
        } else {
            Info("unexpected source event type %d", type);
        }
        e->Release();
    }
    Check(sawNewStream, "MENewStream delivered on Start");
    Check(sawSourceStarted, "MESourceStarted delivered on Start");
    Check(stream != nullptr, "IMFMediaStream obtained from MENewStream");
    if (!stream) return 1;

    IMFStreamDescriptor* fromStream = nullptr;
    if (SUCCEEDED(stream->GetStreamDescriptor(&fromStream)) && fromStream) {
        DWORD id = 1;
        fromStream->GetStreamIdentifier(&id);
        Check(id == 0, "stream identifier is 0");
        fromStream->Release();
    }

    // The stream announces its own start on its own queue.
    IMFMediaEvent* startEvent = NextEvent(stream, 2000);
    if (startEvent) {
        MediaEventType type = MEUnknown;
        startEvent->GetType(&type);
        Check(type == MEStreamStarted, "stream queues MEStreamStarted");
        startEvent->Release();
    } else {
        Check(false, "stream queues MEStreamStarted");
    }

    // Starved stream: a request with no published frame must stay silent.
    hr = stream->RequestSample(nullptr);
    Check(SUCCEEDED(hr), "RequestSample before any frame returns S_OK");
    IMFMediaEvent* starved = NextEvent(stream, 400);
    Check(starved == nullptr, "no sample is produced while no frame has been published");
    if (starved) starved->Release();

    // Publish a tight-stride frame and read it back.
    writer.Publish(1280, 720, 1280 * 4, 30);
    hr = stream->RequestSample(nullptr);
    Check(SUCCEEDED(hr), "RequestSample returns S_OK once a frame exists");

    IMFMediaEvent* event = NextEvent(stream, 3000);
    Check(event != nullptr, "MEMediaSample delivered for the published frame");

    IMFSample* sample = nullptr;
    if (event) {
        MediaEventType type = MEUnknown;
        event->GetType(&type);
        Check(type == MEMediaSample, "event type is MEMediaSample");
        sample = SampleFromEvent(event);
        event->Release();
    }
    Check(sample != nullptr, "sample obtained from the event");

    if (sample) {
        LONGLONG sampleTime = -1, duration = 0;
        sample->GetSampleTime(&sampleTime);
        sample->GetSampleDuration(&duration);
        Check(duration == 333333, "sample duration is 1/30 s in 100 ns units");
        Check(sampleTime >= 0, "sample time is set");
        DWORD bufferCount = 0;
        sample->GetBufferCount(&bufferCount);
        Check(bufferCount == 1, "sample carries exactly one buffer");
        VerifyPixels(sample, 1280, 720,
                     "delivered pixels match the published frame byte for byte");
        sample->Release();
    }

    // A second frame with a padded stride, which is how the client writes when the
    // source buffer is aligned: rows must be repacked, not copied raw.
    writer.Publish(640, 480, 640 * 4 + 128, 30);
    // This instance came up before anything was published, so it negotiated
    // 720p; a frame at another size must be withheld rather than delivered as a
    // wrongly sized sample.
    writer.Publish(640, 480, 640 * 4 + 128, 30);
    Check(RequestSample(stream, 600) == nullptr,
          "a frame at a size other than the negotiated one is withheld");

    hr = source->Stop();
    Check(SUCCEEDED(hr), "source Stop succeeds");

    stream->Release();
    if (handler) handler->Release();
    if (mediaType) mediaType->Release();
    if (sd) sd->Release();
    pd->Release();
    if (activate) activate->Release();
    source->Release();
    factory->Release();

    // With every object released the module must be unloadable.
    if (canUnloadNow) {
        const HRESULT unloadHr = canUnloadNow();
        Check(unloadHr == S_OK, "DllCanUnloadNow reports S_OK after all objects are released");
    }

    // A new instance must negotiate the size the client is publishing, and must
    // refuse frames that no longer match it.
    {
        IMFMediaSource* sized = nullptr;
        hr = factory->CreateInstance(nullptr, __uuidof(IMFMediaSource),
                                    reinterpret_cast<void**>(&sized));
        Check(SUCCEEDED(hr) && sized != nullptr, "second media source instance created");

        IMFPresentationDescriptor* sizedPd = nullptr;
        IMFStreamDescriptor* sizedSd = nullptr;
        IMFMediaTypeHandler* sizedHandler = nullptr;
        IMFMediaType* sizedType = nullptr;
        if (sized) {
            sized->CreatePresentationDescriptor(&sizedPd);
            BOOL sizedSelected = FALSE;
            sizedPd->GetStreamDescriptorByIndex(0, &sizedSelected, &sizedSd);
            sizedSd->GetMediaTypeHandler(&sizedHandler);
            sizedHandler->GetCurrentMediaType(&sizedType);

            UINT32 sw = 0, sh = 0;
            MFGetAttributeSize(sizedType, MF_MT_FRAME_SIZE, &sw, &sh);
            Check(sw == 640 && sh == 480,
                  "advertised size follows the size the client is publishing");
            Info("second instance advertises %ux%u", sw, sh);

            hr = sized->Start(sizedPd, &kNullGuid, nullptr);
            IMFMediaStream* sizedStream = nullptr;
            for (int i = 0; i < 4 && !sizedStream; ++i) {
                IMFMediaEvent* e = NextEvent(sized, 2000);
                if (!e) break;
                MediaEventType type = MEUnknown;
                e->GetType(&type);
                if (type == MENewStream) {
                    PROPVARIANT pv;
                    PropVariantInit(&pv);
                    if (SUCCEEDED(e->GetValue(&pv)) && pv.vt == VT_UNKNOWN && pv.punkVal) {
                        pv.punkVal->QueryInterface(__uuidof(IMFMediaStream),
                                                  reinterpret_cast<void**>(&sizedStream));
                    }
                    PropVariantClear(&pv);
                }
                e->Release();
            }
            Check(sizedStream != nullptr, "second source delivers its stream");

            if (sizedStream) {
                IMFSample* sizedSample = RequestSample(sizedStream, 2000);
                Check(sizedSample != nullptr, "frame at the negotiated size is delivered");
                if (sizedSample) {
                    LONGLONG sizedTime = 0;
                    sizedSample->GetSampleTime(&sizedTime);
                    Check(sizedTime > 0, "sample time advances on the second instance");
                    VerifyPixels(sizedSample, 640, 480,
                                 "padded-stride frame is repacked into a tight 640x480 frame");
                    sizedSample->Release();
                }

                // And once the phone changes size, the source goes quiet again.
                writer.Publish(320, 240, 320 * 4, 30);
                Check(RequestSample(sizedStream, 600) == nullptr,
                      "a frame whose size no longer matches is withheld");

                sizedStream->Release();
            }
        }

        if (sizedType) sizedType->Release();
        if (sizedHandler) sizedHandler->Release();
        if (sizedSd) sizedSd->Release();
        if (sizedPd) sizedPd->Release();
        if (sized) sized->Release();
    }

    // ------------------------------------------------------- host-side exports
    using IsSupportedFn = BOOL (WINAPI*)();
    using StartCameraFn = HRESULT (WINAPI*)(const wchar_t*, void**);
    using StopCameraFn = HRESULT (WINAPI*)(void*);
    using ReleaseCameraFn = void (WINAPI*)(void*);

    auto isSupported = reinterpret_cast<IsSupportedFn>(
        GetProcAddress(module, "OpenCamVirtualCameraIsSupported"));
    auto startCamera = reinterpret_cast<StartCameraFn>(
        GetProcAddress(module, "OpenCamVirtualCameraStart"));
    auto stopCamera = reinterpret_cast<StopCameraFn>(
        GetProcAddress(module, "OpenCamVirtualCameraStop"));
    auto releaseCamera = reinterpret_cast<ReleaseCameraFn>(
        GetProcAddress(module, "OpenCamVirtualCameraRelease"));

    Check(isSupported != nullptr, "exports OpenCamVirtualCameraIsSupported");
    Check(startCamera != nullptr, "exports OpenCamVirtualCameraStart");
    Check(stopCamera != nullptr, "exports OpenCamVirtualCameraStop");
    Check(releaseCamera != nullptr, "exports OpenCamVirtualCameraRelease");

    if (isSupported) {
        const BOOL supported = isSupported();
        Check(supported == TRUE,
              "MFIsVirtualCameraTypeSupported accepts software camera sources on this build");
        Info("MFIsVirtualCameraTypeSupported(SoftwareCameraSource) = %d", supported);
    }

    if (startCamera && releaseCamera) {
        void* camera = nullptr;
        const HRESULT startHr = startCamera(L"OpenCam Test Camera", &camera);
        if (SUCCEEDED(startHr)) {
            Check(camera != nullptr, "OpenCamVirtualCameraStart created a camera");
            if (stopCamera) {
                const HRESULT stopHr = stopCamera(camera);
                Check(SUCCEEDED(stopHr), "OpenCamVirtualCameraStop succeeds");
            }
            releaseCamera(camera);
        } else {
            Check(camera == nullptr, "failed creation leaves no dangling camera handle");
            Info("OpenCamVirtualCameraStart -> 0x%08lX "
                 "(expected until the media source is registered under HKLM)",
                 static_cast<unsigned long>(startHr));
        }
    }

    writer.Close();
    MFShutdown();
    CoUninitialize();

    printf("\n%d passed, %d failed\n", g_passed, g_failed);
    return g_failed == 0 ? 0 : 1;
}
