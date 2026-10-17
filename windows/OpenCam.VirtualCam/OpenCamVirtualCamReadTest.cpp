// Cross-language check for the shared frame buffer.
//
// The C# client publishes frames with SharedFrameBuffer; this program pulls a
// sample out of the media source that reads the very same section and compares
// the pixels. Running it against a live client is what proves the two languages
// agree on the 56 byte header, the seqlock and the pixel offset, rather than each
// being self-consistent on its own.
//
// Usage: OpenCamVirtualCamReadTest.exe [expectedWidth expectedHeight]

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mferror.h>

#include <cstdio>
#include <cstdlib>

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")

static const GUID kClsid =
    {0x6f1b4c93, 0x7a2e, 0x4d71, {0x9c, 0x58, 0x2e, 0x4a, 0x0b, 0x7d, 0x5f, 0x31}};

static int g_passed = 0;
static int g_failed = 0;

static void Check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
        printf("  PASS  %s\n", what);
    } else {
        ++g_failed;
        printf("  FAIL  %s\n", what);
    }
}

static IMFMediaEvent* NextEvent(IMFMediaEventGenerator* generator, DWORD ms) {
    const DWORD start = GetTickCount();
    for (;;) {
        IMFMediaEvent* event = nullptr;
        const HRESULT hr = generator->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
        if (hr == MF_E_NO_EVENTS_AVAILABLE) {
            if (GetTickCount() - start > ms) return nullptr;
            Sleep(5);
            continue;
        }
        if (FAILED(hr)) return nullptr;
        return event;
    }
}

static IMFSample* NextSample(IMFMediaStream* stream, DWORD ms) {
    const DWORD start = GetTickCount();
    while (GetTickCount() - start <= ms) {
        if (FAILED(stream->RequestSample(nullptr))) return nullptr;

        for (int i = 0; i < 8; ++i) {
            IMFMediaEvent* event = NextEvent(stream, 250);
            if (!event) break;

            MediaEventType type = MEUnknown;
            event->GetType(&type);
            IMFSample* sample = nullptr;
            if (type == MEMediaSample) {
                PROPVARIANT pv;
                PropVariantInit(&pv);
                if (SUCCEEDED(event->GetValue(&pv)) && pv.vt == VT_UNKNOWN && pv.punkVal) {
                    pv.punkVal->QueryInterface(__uuidof(IMFSample),
                                              reinterpret_cast<void**>(&sample));
                }
                PropVariantClear(&pv);
            }
            event->Release();
            if (sample) return sample;
        }
    }
    return nullptr;
}

/// Mirrors the pattern the client publishes: blue = x, green = y, red is a mix.
static bool PatternMatches(const BYTE* pixels, int stride, int x, int y) {
    const BYTE* p = pixels + static_cast<size_t>(stride) * y + static_cast<size_t>(x) * 4;
    return p[0] == static_cast<BYTE>(x & 0xFF)
        && p[1] == static_cast<BYTE>(y & 0xFF)
        && p[2] == static_cast<BYTE>((x * 7 + y * 13) & 0xFF)
        && p[3] == 0xFF;
}

int main(int argc, char** argv) {
    const int expectWidth = argc > 1 ? atoi(argv[1]) : 0;
    const int expectHeight = argc > 2 ? atoi(argv[2]) : 0;

    printf("Reading frames published by the OpenCam client\n\n");

    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);

    HMODULE module = LoadLibraryW(L"build\\OpenCamVirtualCam.dll");
    if (!module) module = LoadLibraryW(L"OpenCamVirtualCam.dll");
    if (!module) {
        printf("  FAIL  could not load OpenCamVirtualCam.dll (error %lu)\n", GetLastError());
        return 1;
    }

    using GetClassObjectFn = HRESULT (STDAPICALLTYPE*)(REFCLSID, REFIID, void**);
    auto getClassObject = reinterpret_cast<GetClassObjectFn>(
        GetProcAddress(module, "DllGetClassObject"));

    IClassFactory* factory = nullptr;
    if (FAILED(getClassObject(kClsid, IID_IClassFactory, reinterpret_cast<void**>(&factory)))) {
        printf("  FAIL  class factory unavailable\n");
        return 1;
    }

    IMFMediaSource* source = nullptr;
    factory->CreateInstance(nullptr, __uuidof(IMFMediaSource), reinterpret_cast<void**>(&source));
    if (!source) {
        printf("  FAIL  media source unavailable\n");
        return 1;
    }

    IMFPresentationDescriptor* pd = nullptr;
    IMFStreamDescriptor* sd = nullptr;
    IMFMediaTypeHandler* handler = nullptr;
    IMFMediaType* mediaType = nullptr;
    BOOL selected = FALSE;
    source->CreatePresentationDescriptor(&pd);
    pd->GetStreamDescriptorByIndex(0, &selected, &sd);
    sd->GetMediaTypeHandler(&handler);
    handler->GetCurrentMediaType(&mediaType);

    UINT32 width = 0, height = 0;
    MFGetAttributeSize(mediaType, MF_MT_FRAME_SIZE, &width, &height);
    printf("  info  media type is %ux%u\n", width, height);

    if (expectWidth > 0) {
        Check(width == static_cast<UINT32>(expectWidth) && height == static_cast<UINT32>(expectHeight),
              "advertised size matches the client's published frame");
    }
    Check(width > 0 && height > 0, "media type carries a usable frame size");

    static const GUID kNullGuid = {0};
    source->Start(pd, &kNullGuid, nullptr);

    IMFMediaStream* stream = nullptr;
    for (int i = 0; i < 6 && !stream; ++i) {
        IMFMediaEvent* event = NextEvent(source, 2000);
        if (!event) break;
        MediaEventType type = MEUnknown;
        event->GetType(&type);
        if (type == MENewStream) {
            PROPVARIANT pv;
            PropVariantInit(&pv);
            if (SUCCEEDED(event->GetValue(&pv)) && pv.vt == VT_UNKNOWN && pv.punkVal) {
                pv.punkVal->QueryInterface(__uuidof(IMFMediaStream),
                                          reinterpret_cast<void**>(&stream));
            }
            PropVariantClear(&pv);
        }
        event->Release();
    }
    Check(stream != nullptr, "stream opened");

    IMFSample* sample = stream ? NextSample(stream, 8000) : nullptr;
    Check(sample != nullptr, "a frame published by the client reaches the media source");

    if (sample) {
        IMFMediaBuffer* buffer = nullptr;
        sample->GetBufferByIndex(0, &buffer);
        Check(buffer != nullptr, "sample carries a buffer");

        if (buffer) {
            DWORD length = 0, maxLength = 0;
            buffer->GetCurrentLength(&length);
            const DWORD expected = width * 4u * height;

            BYTE* pixels = nullptr;
            if (SUCCEEDED(buffer->Lock(&pixels, &maxLength, nullptr)) && pixels) {
                Check(length == expected, "buffer length matches the negotiated frame size");

                const int stride = static_cast<int>(width) * 4;
                bool all = true;
                const int probes[][2] = {
                    {0, 0}, {1, 0}, {static_cast<int>(width) - 1, 0},
                    {0, static_cast<int>(height) - 1},
                    {static_cast<int>(width) - 1, static_cast<int>(height) - 1},
                    {static_cast<int>(width) / 2, static_cast<int>(height) / 2},
                };
                for (const auto& probe : probes) {
                    if (!PatternMatches(pixels, stride, probe[0], probe[1])) {
                        all = false;
                        printf("  info  mismatch at (%d,%d)\n", probe[0], probe[1]);
                    }
                }
                Check(all, "pixels match the pattern the client published");

                LONGLONG sampleTime = 0;
                sample->GetSampleTime(&sampleTime);
                Check(sampleTime >= 0, "sample time is set");
                buffer->Unlock();
            }
            buffer->Release();
        }
        sample->Release();
    }

    if (stream) stream->Release();
    mediaType->Release();
    handler->Release();
    sd->Release();
    pd->Release();
    source->Release();
    factory->Release();

    MFShutdown();
    CoUninitialize();

    printf("\n%d passed, %d failed\n", g_passed, g_failed);
    return g_failed == 0 ? 0 : 1;
}
