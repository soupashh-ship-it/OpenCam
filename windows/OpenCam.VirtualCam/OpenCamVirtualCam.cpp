// OpenCam virtual camera media source.
//
// Windows 11 can host a software camera through the Media Foundation virtual
// camera API. The application calls MFCreateVirtualCamera with the CLSID of a
// registered media source; this DLL is that media source. It does not talk to
// the phone itself. The OpenCam client decodes frames and publishes them into a
// named shared memory section, and this source hands those frames to whichever
// process is consuming the camera.
//
// That split keeps networking and JPEG decoding in the managed client and keeps
// this module small enough to audit: it only reads shared memory and builds
// Media Foundation samples.
//
// Build:  build.bat
// Install: register.bat   (elevated, once)

#include "OpenCamVirtualCam.h"

#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mfobjects.h>
#include <mfvirtualcamera.h>
#include <ks.h>
#include <ksmedia.h>
#include <devicetopology.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <new>
#include <string>

// {6F1B4C93-7A2E-4D71-9C58-2E4A0B7D5F31}
const GUID CLSID_OpenCamVirtualCam =
    {0x6f1b4c93, 0x7a2e, 0x4d71, {0x9c, 0x58, 0x2e, 0x4a, 0x0b, 0x7d, 0x5f, 0x31}};

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shlwapi.lib")

namespace opencam {

// ---------------------------------------------------------------- ref counting

/// Minimal ref-count base shared by the source, the stream and the factory.
class RefCounted {
public:
    virtual ~RefCounted() = default;

    ULONG AddRef() { return static_cast<ULONG>(InterlockedIncrement(&_ref)); }

    ULONG Release() {
        const ULONG remaining = static_cast<ULONG>(InterlockedDecrement(&_ref));
        if (remaining == 0) delete this;
        return remaining;
    }

protected:
    volatile LONG _ref = 1;
};

// ------------------------------------------------------------ shared frame read

/// Reads whole frames out of the shared section the client writes to.
class SharedFrameReader {
public:
    ~SharedFrameReader() { Close(); }

    bool Open() {
        if (_base) return true;

        _mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, kSharedSectionName);
        if (!_mapping) {
            // A client that has not started yet is not an error; the consumer will
            // simply see no frames until one does.
            return false;
        }

        _base = static_cast<const BYTE*>(MapViewOfFile(_mapping, FILE_MAP_READ, 0, 0, 0));
        if (!_base) {
            CloseHandle(_mapping);
            _mapping = nullptr;
            return false;
        }

        _size = sizeof(OpenCamFrameHeader) + static_cast<size_t>(kMaxWidth) * kMaxHeight * 4;
        return true;
    }

    void Close() {
        if (_base) {
            UnmapViewOfFile(_base);
            _base = nullptr;
        }
        if (_mapping) {
            CloseHandle(_mapping);
            _mapping = nullptr;
        }
    }

    bool IsOpen() const { return _base != nullptr; }

    const OpenCamFrameHeader* Header() const {
        return reinterpret_cast<const OpenCamFrameHeader*>(_base);
    }

    /// True when the header describes a frame this module can serve.
    bool IsUsable(const OpenCamFrameHeader& header) const {
        if (header.magic != kFrameMagic) return false;
        if (header.version != kFrameLayoutVersion) return false;
        if (header.pixelFormat != kPixelFormatBgra32) return false;
        if (header.width <= 0 || header.height <= 0) return false;
        if (header.width > kMaxWidth || header.height > kMaxHeight) return false;

        const LONG expectedStride = header.width * 4;
        if (header.stride < expectedStride) return false;
        if (static_cast<size_t>(header.stride) * header.height + sizeof(OpenCamFrameHeader) > _size)
            return false;
        return true;
    }

    const BYTE* Pixels() const { return _base + sizeof(OpenCamFrameHeader); }

    /// Current frame counter, used to detect that a new frame has arrived.
    LONGLONG FrameCounter() const {
        if (!_base) return 0;
        return Header()->frameCounter;
    }

    /// Waits for a frame newer than <paramref name="lastSeen"/> to appear.
    /// Returns false when the timeout expires.
    bool WaitForFrame(LONGLONG lastSeen, DWORD timeoutMs) {
        const DWORD start = GetTickCount();
        while (GetTickCount() - start < timeoutMs) {
            if (!_base && !Open()) {
                Sleep(20);
                continue;
            }
            const OpenCamFrameHeader* header = Header();
            if (header->magic == kFrameMagic && header->frameCounter > lastSeen)
                return true;
            Sleep(4);
        }
        return false;
    }

private:
    HANDLE _mapping = nullptr;
    const BYTE* _base = nullptr;
    size_t _size = 0;
};

/// Copies one frame out of shared memory without ever tearing.
///
/// The client uses a seqlock, so the sequence number is re-read after the copy:
/// if it changed, or was odd, the copy raced the writer and is discarded.
bool CopyFrame(const SharedFrameReader& reader,
               BYTE* destination,
               DWORD destinationSize,
               OpenCamFrameHeader& outHeader) {
    for (int attempt = 0; attempt < 32; ++attempt) {
        const OpenCamFrameHeader* header = reader.Header();
        const LONGLONG before = header->sequence;

        if (before & 1) {
            Sleep(1);
            continue;
        }

        OpenCamFrameHeader snapshot = *header;
        if (!reader.IsUsable(snapshot)) return false;

        const SIZE_T rowBytes = static_cast<SIZE_T>(snapshot.width) * 4;
        const SIZE_T total = rowBytes * snapshot.height;
        if (total > destinationSize) return false;

        const BYTE* source = reader.Pixels();
        for (INT32 row = 0; row < snapshot.height; ++row) {
            memcpy(
                destination + rowBytes * row,
                source + static_cast<SIZE_T>(snapshot.stride) * row,
                rowBytes);
        }

        if (header->sequence == before) {
            outHeader = snapshot;
            return true;
        }

        Sleep(1);
    }
    return false;
}

// ------------------------------------------------------------------- media stream

class OpenCamMediaSource;

class OpenCamMediaStream final
    : public RefCounted,
      public IMFMediaStream {
public:
    OpenCamMediaStream(OpenCamMediaSource* source, IMFStreamDescriptor* descriptor,
                       INT32 width, INT32 height)
        : _source(source), _descriptor(descriptor), _width(width), _height(height) {
        _descriptor->AddRef();
        MFCreateEventQueue(&_events);
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override { return RefCounted::AddRef(); }
    STDMETHODIMP_(ULONG) Release() override { return RefCounted::Release(); }

    // IMFMediaEventGenerator
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override;
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override;
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended, HRESULT status,
                            const PROPVARIANT* value) override;

    // IMFMediaStream
    STDMETHODIMP GetMediaSource(IMFMediaSource** source) override;
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** descriptor) override;
    STDMETHODIMP RequestSample(IUnknown* token) override;

    // Internal
    HRESULT Start();
    HRESULT Stop();
    HRESULT Pause();
    HRESULT Shutdown();
    HRESULT SetShutdown() { _shutdown = true; return S_OK; }

    MediaEventType State() const { return _state; }

private:
    ~OpenCamMediaStream() {
        if (_descriptor) _descriptor->Release();
        if (_events) _events->Release();
    }

    HRESULT BuildSample(IMFSample** sample);

    OpenCamMediaSource* _source;
    IMFStreamDescriptor* _descriptor = nullptr;
    IMFMediaEventQueue* _events = nullptr;

    volatile bool _shutdown = false;
    MediaEventType _state = MEStreamStopped;

    SharedFrameReader _reader;
    LONGLONG _lastDelivered = 0;
    LONGLONG _timestamp = 0;

    // The frame size agreed with the consumer in the media type.
    INT32 _width;
    INT32 _height;
};

// ------------------------------------------------------------------- media source

// IMFMediaSourceEx rather than plain IMFMediaSource: the virtual camera host
// asks a software camera source for it and fails activation with E_NOINTERFACE
// when the source does not implement it (observed here as 0x80004002 from
// MFCreateVirtualCamera).
class OpenCamMediaSource final
    : public RefCounted,
      public IMFMediaSourceEx,
      public IMFActivate,
      public IMFGetService,
      public IKsControl {
public:
    OpenCamMediaSource() {
        MFCreateEventQueue(&_events);
        MFCreateAttributes(&_attributes, 4);
        MFCreateAttributes(&_streamAttributes, 2);
    }

    static HRESULT CreateInstance(REFIID riid, void** ppv) {
        auto* source = new (std::nothrow) OpenCamMediaSource();
        if (!source) return E_OUTOFMEMORY;
        HRESULT hr = source->Initialize();
        if (SUCCEEDED(hr)) hr = source->QueryInterface(riid, ppv);
        source->Release();
        return hr;
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override { return RefCounted::AddRef(); }
    STDMETHODIMP_(ULONG) Release() override { return RefCounted::Release(); }

    // IMFMediaEventGenerator
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override;
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override;
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override;
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID extended, HRESULT status,
                            const PROPVARIANT* value) override;

    // IMFMediaSource
    STDMETHODIMP GetCharacteristics(DWORD* characteristics) override;
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** pd) override;
    STDMETHODIMP Start(IMFPresentationDescriptor* pd, const GUID* timeFormat,
                       const PROPVARIANT* startPosition) override;
    STDMETHODIMP Stop() override;
    STDMETHODIMP Pause() override;
    STDMETHODIMP Shutdown() override;

    // IMFMediaSourceEx
    STDMETHODIMP GetSourceAttributes(IMFAttributes** attributes) override;
    STDMETHODIMP GetStreamAttributes(DWORD streamId, IMFAttributes** attributes) override;
    STDMETHODIMP SetD3DManager(IUnknown* manager) override;

    // IMFActivate
    STDMETHODIMP ActivateObject(REFIID riid, void** ppv) override;
    STDMETHODIMP DetachObject() override;
    STDMETHODIMP ShutdownObject() override;

    // IMFGetService
    STDMETHODIMP GetService(REFGUID service, REFGUID riid, void** ppv) override;

    // IKsControl. The frame server probes for it the way it does on a real
    // camera driver; OpenCam exposes no KS properties, so every call reports that
    // the property set is absent, which is what an AV Stream miniport returns.
    STDMETHODIMP KsProperty(PKSPROPERTY property, ULONG length, LPVOID data,
                            ULONG dataLength, ULONG* bytesReturned) override;
    STDMETHODIMP KsMethod(PKSMETHOD method, ULONG length, LPVOID data,
                          ULONG dataLength, ULONG* bytesReturned) override;
    STDMETHODIMP KsEvent(PKSEVENT event, ULONG length, LPVOID data,
                         ULONG dataLength, ULONG* bytesReturned) override;

    // IMFAttributes: delegated to an internal attribute store. The virtual camera
    // machinery reads a few attributes off the activate object, and delegating
    // avoids reimplementing the whole interface.
    STDMETHODIMP GetItem(REFGUID key, PROPVARIANT* value) override { return Trace("GetItem", key, _attributes->GetItem(key, value)); }
    STDMETHODIMP GetItemType(REFGUID key, MF_ATTRIBUTE_TYPE* type) override { return _attributes->GetItemType(key, type); }
    STDMETHODIMP CompareItem(REFGUID key, REFPROPVARIANT value, BOOL* result) override { return _attributes->CompareItem(key, value, result); }
    STDMETHODIMP Compare(IMFAttributes* theirs, MF_ATTRIBUTES_MATCH_TYPE type, BOOL* result) override { return _attributes->Compare(theirs, type, result); }
    STDMETHODIMP GetUINT32(REFGUID key, UINT32* value) override { return Trace("GetUINT32", key, _attributes->GetUINT32(key, value)); }
    STDMETHODIMP GetUINT64(REFGUID key, UINT64* value) override { return Trace("GetUINT64", key, _attributes->GetUINT64(key, value)); }
    STDMETHODIMP GetDouble(REFGUID key, double* value) override { return _attributes->GetDouble(key, value); }
    STDMETHODIMP GetGUID(REFGUID key, GUID* value) override { return _attributes->GetGUID(key, value); }
    STDMETHODIMP GetStringLength(REFGUID key, UINT32* length) override { return _attributes->GetStringLength(key, length); }
    STDMETHODIMP GetString(REFGUID key, LPWSTR value, UINT32 size, UINT32* length) override { return Trace("GetString", key, _attributes->GetString(key, value, size, length)); }
    STDMETHODIMP GetAllocatedString(REFGUID key, LPWSTR* value, UINT32* length) override { return _attributes->GetAllocatedString(key, value, length); }
    STDMETHODIMP GetBlobSize(REFGUID key, UINT32* size) override { return _attributes->GetBlobSize(key, size); }
    STDMETHODIMP GetBlob(REFGUID key, UINT8* buffer, UINT32 size, UINT32* blobSize) override { return _attributes->GetBlob(key, buffer, size, blobSize); }
    STDMETHODIMP GetAllocatedBlob(REFGUID key, UINT8** buffer, UINT32* size) override { return _attributes->GetAllocatedBlob(key, buffer, size); }
    STDMETHODIMP GetUnknown(REFGUID key, REFIID riid, LPVOID* ppv) override { return Trace("GetUnknown", key, _attributes->GetUnknown(key, riid, ppv)); }
    STDMETHODIMP SetItem(REFGUID key, REFPROPVARIANT value) override { return _attributes->SetItem(key, value); }
    STDMETHODIMP DeleteItem(REFGUID key) override { return _attributes->DeleteItem(key); }
    STDMETHODIMP DeleteAllItems() override { return _attributes->DeleteAllItems(); }
    STDMETHODIMP SetUINT32(REFGUID key, UINT32 value) override { return _attributes->SetUINT32(key, value); }
    STDMETHODIMP SetUINT64(REFGUID key, UINT64 value) override { return _attributes->SetUINT64(key, value); }
    STDMETHODIMP SetDouble(REFGUID key, double value) override { return _attributes->SetDouble(key, value); }
    STDMETHODIMP SetGUID(REFGUID key, REFGUID value) override { return _attributes->SetGUID(key, value); }
    STDMETHODIMP SetString(REFGUID key, LPCWSTR value) override { return _attributes->SetString(key, value); }
    STDMETHODIMP SetBlob(REFGUID key, const UINT8* buffer, UINT32 size) override { return _attributes->SetBlob(key, buffer, size); }
    STDMETHODIMP SetUnknown(REFGUID key, IUnknown* unknown) override { return _attributes->SetUnknown(key, unknown); }
    STDMETHODIMP LockStore() override { return _attributes->LockStore(); }
    STDMETHODIMP UnlockStore() override { return _attributes->UnlockStore(); }
    STDMETHODIMP GetCount(UINT32* count) override { return _attributes->GetCount(count); }
    STDMETHODIMP GetItemByIndex(UINT32 index, GUID* key, PROPVARIANT* value) override { return _attributes->GetItemByIndex(index, key, value); }
    STDMETHODIMP CopyAllItems(IMFAttributes* destination) override { return _attributes->CopyAllItems(destination); }

    IMFStreamDescriptor* StreamDescriptor() const { return _streamDescriptor; }

private:
    /// Records a failed attribute lookup, which is how a host signals the one
    /// attribute it insisted on. Quiet unless OPENCAM_QI_LOG is set.
    static HRESULT Trace(const char* getter, REFGUID key, HRESULT hr);

    ~OpenCamMediaSource() {
        if (_presentationDescriptor) _presentationDescriptor->Release();
        if (_streamDescriptor) _streamDescriptor->Release();
        if (_stream) _stream->Release();
        if (_events) _events->Release();
        if (_streamAttributes) _streamAttributes->Release();
        if (_attributes) _attributes->Release();
    }

    HRESULT Initialize();

    IMFMediaEventQueue* _events = nullptr;
    IMFAttributes* _attributes = nullptr;
    IMFAttributes* _streamAttributes = nullptr;
    IMFPresentationDescriptor* _presentationDescriptor = nullptr;
    IMFStreamDescriptor* _streamDescriptor = nullptr;
    OpenCamMediaStream* _stream = nullptr;

    // Frame size advertised in the media type, taken from the client's stream when
    // it is already publishing and 720p otherwise.
    INT32 _frameWidth = 1280;
    INT32 _frameHeight = 720;

    volatile bool _shutdown = false;
    bool _started = false;
};

// -------------------------------------------------------------------- stream impl

STDMETHODIMP OpenCamMediaStream::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;

    if (riid == IID_IUnknown || riid == __uuidof(IMFMediaEventGenerator)) {
        *ppv = static_cast<IMFMediaEventGenerator*>(this);
    } else if (riid == __uuidof(IMFMediaStream)) {
        *ppv = static_cast<IMFMediaStream*>(this);
    } else {
        return E_NOINTERFACE;
    }

    AddRef();
    return S_OK;
}

STDMETHODIMP OpenCamMediaStream::GetEvent(DWORD flags, IMFMediaEvent** event) {
    return _events->GetEvent(flags, event);
}

STDMETHODIMP OpenCamMediaStream::BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) {
    return _events->BeginGetEvent(callback, state);
}

STDMETHODIMP OpenCamMediaStream::EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) {
    return _events->EndGetEvent(result, event);
}

STDMETHODIMP OpenCamMediaStream::QueueEvent(MediaEventType type, REFGUID extended,
                                            HRESULT status, const PROPVARIANT* value) {
    return _events->QueueEventParamVar(type, extended, status, value);
}

STDMETHODIMP OpenCamMediaStream::GetMediaSource(IMFMediaSource** source) {
    if (!source) return E_POINTER;
    if (_shutdown) return MF_E_SHUTDOWN;
    return _source->QueryInterface(__uuidof(IMFMediaSource), reinterpret_cast<void**>(source));
}

STDMETHODIMP OpenCamMediaStream::GetStreamDescriptor(IMFStreamDescriptor** descriptor) {
    if (!descriptor) return E_POINTER;
    if (_shutdown) return MF_E_SHUTDOWN;
    *descriptor = _descriptor;
    _descriptor->AddRef();
    return S_OK;
}

HRESULT OpenCamMediaStream::Start() {
    if (_shutdown) return MF_E_SHUTDOWN;
    _state = MEStreamStarted;
    _reader.Open();
    _lastDelivered = 0;
    _timestamp = 0;
    return QueueEvent(MEStreamStarted, GUID_NULL, S_OK, nullptr);
}

HRESULT OpenCamMediaStream::Stop() {
    if (_shutdown) return MF_E_SHUTDOWN;
    _state = MEStreamStopped;
    _reader.Close();
    return QueueEvent(MEStreamStopped, GUID_NULL, S_OK, nullptr);
}

HRESULT OpenCamMediaStream::Pause() {
    if (_shutdown) return MF_E_SHUTDOWN;
    _state = MEStreamPaused;
    return QueueEvent(MEStreamPaused, GUID_NULL, S_OK, nullptr);
}

HRESULT OpenCamMediaStream::Shutdown() {
    _shutdown = true;
    _reader.Close();
    const HRESULT hr = _events->Shutdown();
    return hr;
}

/// Builds one Media Foundation sample from the newest frame in shared memory.
HRESULT OpenCamMediaStream::BuildSample(IMFSample** sample) {
    *sample = nullptr;

    if (!_reader.IsOpen() && !_reader.Open()) return MF_E_NOT_FOUND;

    // Wait for a frame the consumer has not seen yet. The wait is short because
    // RequestSample is a synchronous call on the consumer's thread: a phone that
    // has gone quiet should look like "no sample right now", not like a stall.
    if (!_reader.WaitForFrame(_lastDelivered, 500)) return MF_E_NOT_FOUND;

    const OpenCamFrameHeader* header = _reader.Header();

    // Deliver only frames matching the media type the consumer agreed to. A
    // mismatch means the phone changed resolution after the camera was created,
    // and passing the sample on would hand the consumer a wrongly sized buffer.
    if (header->width != _width || header->height != _height) return MF_E_NOT_FOUND;

    const DWORD frameBytes = static_cast<DWORD>(_width) * 4u * static_cast<DWORD>(_height);

    IMFSample* newSample = nullptr;
    IMFMediaBuffer* buffer = nullptr;
    BYTE* destination = nullptr;

    HRESULT hr = MFCreateSample(&newSample);
    if (SUCCEEDED(hr)) hr = MFCreateMemoryBuffer(frameBytes, &buffer);
    if (SUCCEEDED(hr)) hr = buffer->Lock(&destination, nullptr, nullptr);

    if (SUCCEEDED(hr)) {
        OpenCamFrameHeader copied{};
        if (CopyFrame(_reader, destination, frameBytes, copied)) {
            buffer->Unlock();
            buffer->SetCurrentLength(frameBytes);
            hr = newSample->AddBuffer(buffer);

            if (SUCCEEDED(hr)) {
                // Advance a synthetic clock rather than using wall time, so the
                // consumer sees an even cadence even if frames arrive unevenly.
                const UINT32 fps = copied.fps > 0 ? static_cast<UINT32>(copied.fps) : kDefaultFps;
                const LONGLONG duration = 10'000'000LL / (fps ? fps : kDefaultFps);
                _timestamp += duration;
                newSample->SetSampleTime(_timestamp);
                newSample->SetSampleDuration(duration);
            }
            _lastDelivered = header->frameCounter;
        } else {
            buffer->Unlock();
            hr = MF_E_NOT_FOUND;
        }
    }

    if (buffer) buffer->Release();

    if (FAILED(hr)) {
        if (newSample) newSample->Release();
        return hr;
    }

    *sample = newSample;
    return S_OK;
}

/// The consumer pulls frames by calling RequestSample; we answer with a
/// MEMediaSample event carrying the sample.
STDMETHODIMP OpenCamMediaStream::RequestSample(IUnknown* token) {
    if (_shutdown) return MF_E_SHUTDOWN;

    IMFSample* sample = nullptr;
    HRESULT hr = BuildSample(&sample);

    if (hr == MF_E_NOT_FOUND) {
        // No frame yet. Report that so the consumer simply asks again rather than
        // treating this as a stream failure.
        return S_OK;
    }
    if (FAILED(hr)) return hr;

    if (token) sample->SetUnknown(MFSampleExtension_Token, token);

    const HRESULT queueResult = _events->QueueEventParamUnk(
        MEMediaSample, GUID_NULL, S_OK, sample);
    sample->Release();
    return queueResult;
}

// ------------------------------------------------------------------- source impl

HRESULT OpenCamMediaSource::Initialize() {
    // The virtual camera host reads this attribute and treats its absence as a
    // failure (MF_E_ATTRIBUTENOTFOUND from Start), rather than as "no associated
    // cameras". OpenCam drives a phone, so it has none to offer.
    _attributes->SetUINT32(MF_VIRTUALCAMERA_PROVIDE_ASSOCIATED_CAMERA_SOURCES, 0);

    IMFMediaType* mediaType = nullptr;
    HRESULT hr = MFCreateMediaType(&mediaType);
    if (FAILED(hr)) return hr;

    mediaType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    mediaType->SetGUID(MF_MT_SUBTYPE, OutputSubtype());
    mediaType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    mediaType->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);

    // Advertise the size the phone is actually streaming, which the client records
    // in the shared frame buffer. Negotiating anything else would mean every sample
    // we deliver disagrees with the agreed media type. Before the client has
    // published anything there is nothing to read, so 720p stands in.
    {
        SharedFrameReader probe;
        if (probe.Open()) {
            const OpenCamFrameHeader* header = probe.Header();
            if (probe.IsUsable(*header)) {
                _frameWidth = header->width;
                _frameHeight = header->height;
            }
        }
    }

    MFSetAttributeSize(mediaType, MF_MT_FRAME_SIZE, static_cast<UINT32>(_frameWidth),
                       static_cast<UINT32>(_frameHeight));
    MFSetAttributeRatio(mediaType, MF_MT_FRAME_RATE, kDefaultFps, 1);
    MFSetAttributeRatio(mediaType, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);

    IMFMediaType** types = new (std::nothrow) IMFMediaType*[1];
    if (!types) {
        mediaType->Release();
        return E_OUTOFMEMORY;
    }
    types[0] = mediaType;

    hr = MFCreateStreamDescriptor(0, 1, types, &_streamDescriptor);
    delete[] types;

    // A video capture stream describes itself through these attributes; the frame
    // server reads them before it will start the source, and a missing one fails
    // Start with MF_E_ATTRIBUTENOTFOUND rather than falling back to a default.
    if (SUCCEEDED(hr)) {
        _streamAttributes->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE);
        _streamAttributes->SetUINT32(MF_DEVICESTREAM_STREAM_ID, 0);
        _streamAttributes->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1);
        _streamAttributes->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES,
                                     MFFrameSourceTypes_Color);
        hr = _streamAttributes->CopyAllItems(_streamDescriptor);
    }

    if (SUCCEEDED(hr)) {
        IMFMediaTypeHandler* handler = nullptr;
        hr = _streamDescriptor->GetMediaTypeHandler(&handler);
        if (SUCCEEDED(hr)) hr = handler->SetCurrentMediaType(mediaType);
        if (handler) handler->Release();
    }
    mediaType->Release();
    if (FAILED(hr)) return hr;

    hr = MFCreatePresentationDescriptor(1, &_streamDescriptor, &_presentationDescriptor);
    if (FAILED(hr)) return hr;

    // The first stream is selected by default.
    _presentationDescriptor->SelectStream(0);

    _stream = new (std::nothrow) OpenCamMediaStream(
        this, _streamDescriptor, _frameWidth, _frameHeight);
    if (!_stream) return E_OUTOFMEMORY;

    return S_OK;
}

/// Appends one line to the diagnostics log, if one is configured.
static void LogLine(const wchar_t* format, ...) {
    wchar_t path[MAX_PATH]{};
    if (!GetEnvironmentVariableW(L"OPENCAM_QI_LOG", path, MAX_PATH)) return;

    FILE* log = nullptr;
    if (_wfopen_s(&log, path, L"a, ccs=UTF-8") != 0 || !log) return;

    va_list args;
    va_start(args, format);
    vfwprintf(log, format, args);
    va_end(args);
    fclose(log);
}

HRESULT OpenCamMediaSource::Trace(const char* getter, REFGUID key, HRESULT hr) {
    if (hr == MF_E_ATTRIBUTENOTFOUND) {
        wchar_t guid[64]{};
        if (StringFromGUID2(key, guid, ARRAYSIZE(guid))) {
            LogLine(L"MISSING %hs %s\n", getter, guid);
        }
    }
    return hr;
}

/// Records an interface a host asked for and did not get.
///
/// When a host fails activation with E_NOINTERFACE this is the only place that
/// knows which interface was missing, so it is worth recording. It stays quiet
/// unless OPENCAM_QI_LOG names a log file, which keeps shipping builds silent.
static void LogUnsupportedInterface(REFIID riid) {
    wchar_t guid[64]{};
    if (!StringFromGUID2(riid, guid, ARRAYSIZE(guid))) return;
    LogLine(L"E_NOINTERFACE %s (thread %lu)\n", guid, GetCurrentThreadId());
}

STDMETHODIMP OpenCamMediaSource::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;

    if (riid == IID_IUnknown || riid == __uuidof(IMFMediaSource)) {
        *ppv = static_cast<IMFMediaSource*>(this);
    } else if (riid == __uuidof(IMFMediaSourceEx)) {
        *ppv = static_cast<IMFMediaSourceEx*>(this);
    } else if (riid == __uuidof(IMFMediaEventGenerator)) {
        *ppv = static_cast<IMFMediaEventGenerator*>(this);
    } else if (riid == __uuidof(IMFActivate)) {
        *ppv = static_cast<IMFActivate*>(this);
    } else if (riid == __uuidof(IMFAttributes)) {
        *ppv = static_cast<IMFAttributes*>(this);
    } else if (riid == __uuidof(IMFGetService)) {
        *ppv = static_cast<IMFGetService*>(this);
    } else if (riid == __uuidof(IKsControl)) {
        *ppv = static_cast<IKsControl*>(this);
    } else {
        LogUnsupportedInterface(riid);
        return E_NOINTERFACE;
    }

    AddRef();
    return S_OK;
}

STDMETHODIMP OpenCamMediaSource::GetEvent(DWORD flags, IMFMediaEvent** event) {
    return _events->GetEvent(flags, event);
}

STDMETHODIMP OpenCamMediaSource::BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) {
    return _events->BeginGetEvent(callback, state);
}

STDMETHODIMP OpenCamMediaSource::EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) {
    return _events->EndGetEvent(result, event);
}

STDMETHODIMP OpenCamMediaSource::QueueEvent(MediaEventType type, REFGUID extended,
                                            HRESULT status, const PROPVARIANT* value) {
    return _events->QueueEventParamVar(type, extended, status, value);
}

STDMETHODIMP OpenCamMediaSource::GetCharacteristics(DWORD* characteristics) {
    if (!characteristics) return E_POINTER;
    if (_shutdown) return MF_E_SHUTDOWN;
    *characteristics = MFMEDIASOURCE_IS_LIVE;
    return S_OK;
}

// --------------------------------------------------------------- IMFMediaSourceEx

STDMETHODIMP OpenCamMediaSource::GetSourceAttributes(IMFAttributes** attributes) {
    if (!attributes) return E_POINTER;
    *attributes = nullptr;
    if (_shutdown) return MF_E_SHUTDOWN;
    *attributes = _attributes;
    _attributes->AddRef();
    return S_OK;
}

STDMETHODIMP OpenCamMediaSource::GetStreamAttributes(DWORD streamId, IMFAttributes** attributes) {
    if (!attributes) return E_POINTER;
    *attributes = nullptr;
    if (_shutdown) return MF_E_SHUTDOWN;
    if (streamId != 0) return MF_E_INVALIDSTREAMNUMBER;
    *attributes = _streamAttributes;
    _streamAttributes->AddRef();
    return S_OK;
}

STDMETHODIMP OpenCamMediaSource::KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG,
                                            ULONG* bytesReturned) {
    if (bytesReturned) *bytesReturned = 0;
    return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

STDMETHODIMP OpenCamMediaSource::KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG,
                                          ULONG* bytesReturned) {
    if (bytesReturned) *bytesReturned = 0;
    return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

STDMETHODIMP OpenCamMediaSource::KsEvent(PKSEVENT, ULONG, LPVOID, ULONG,
                                         ULONG* bytesReturned) {
    if (bytesReturned) *bytesReturned = 0;
    return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
}

/// Frames are CPU buffers in shared memory, so a DXGI device manager has nothing
/// to do here. Accepting it quietly keeps a D3D-aware host from treating the
/// camera as broken.
STDMETHODIMP OpenCamMediaSource::SetD3DManager(IUnknown*) {
    if (_shutdown) return MF_E_SHUTDOWN;
    return S_OK;
}

STDMETHODIMP OpenCamMediaSource::CreatePresentationDescriptor(IMFPresentationDescriptor** pd) {
    if (!pd) return E_POINTER;
    if (_shutdown) return MF_E_SHUTDOWN;
    *pd = _presentationDescriptor;
    _presentationDescriptor->AddRef();
    return S_OK;
}

STDMETHODIMP OpenCamMediaSource::Start(IMFPresentationDescriptor* pd, const GUID* timeFormat,
                                       const PROPVARIANT* startPosition) {
    // The stream runs on the presentation clock; the requested time format and
    // start position are not meaningful for a live source.
    UNREFERENCED_PARAMETER(timeFormat);
    UNREFERENCED_PARAMETER(startPosition);
    if (_shutdown) return MF_E_SHUTDOWN;
    if (_started) return MF_E_INVALIDREQUEST;

    BOOL selected = FALSE;
    IMFStreamDescriptor* descriptor = nullptr;
    HRESULT hr = pd->GetStreamDescriptorByIndex(0, &selected, &descriptor);
    if (FAILED(hr)) return hr;

    if (selected && descriptor && _stream) {
        // Hand the consumer the stream before telling it playback started.
        hr = _events->QueueEventParamUnk(MENewStream, GUID_NULL, S_OK, _stream);
        if (SUCCEEDED(hr)) hr = _stream->Start();
    }

    descriptor->Release();
    if (FAILED(hr)) return hr;

    _started = true;
    return _events->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, startPosition);
}

STDMETHODIMP OpenCamMediaSource::Stop() {
    if (_shutdown) return MF_E_SHUTDOWN;
    _started = false;
    if (_stream) _stream->Stop();
    return _events->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
}

STDMETHODIMP OpenCamMediaSource::Pause() {
    if (_shutdown) return MF_E_SHUTDOWN;
    if (_stream) _stream->Pause();
    return _events->QueueEventParamVar(MESourcePaused, GUID_NULL, S_OK, nullptr);
}

STDMETHODIMP OpenCamMediaSource::Shutdown() {
    if (_shutdown) return S_OK;
    _shutdown = true;
    if (_stream) _stream->Shutdown();
    return _events->Shutdown();
}

STDMETHODIMP OpenCamMediaSource::ActivateObject(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    return QueryInterface(riid, ppv);
}

STDMETHODIMP OpenCamMediaSource::DetachObject() {
    return S_OK;
}

STDMETHODIMP OpenCamMediaSource::ShutdownObject() {
    return Shutdown();
}

STDMETHODIMP OpenCamMediaSource::GetService(REFGUID, REFGUID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    return QueryInterface(riid, ppv);
}

// ------------------------------------------------------------------ class factory

/// Outstanding server locks, exported for DllCanUnloadNow.
volatile LONG _locks = 0;

class OpenCamClassFactory final : public RefCounted, public IClassFactory {
public:
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        return E_NOINTERFACE;
    }

    STDMETHODIMP_(ULONG) AddRef() override { return RefCounted::AddRef(); }
    STDMETHODIMP_(ULONG) Release() override { return RefCounted::Release(); }

    STDMETHODIMP CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override {
        if (outer) return CLASS_E_NOAGGREGATION;
        return OpenCamMediaSource::CreateInstance(riid, ppv);
    }

    STDMETHODIMP LockServer(BOOL lock) override {
        if (lock) InterlockedIncrement(&_locks);
        else InterlockedDecrement(&_locks);
        return S_OK;
    }

private:
    ~OpenCamClassFactory() = default;
};

} // namespace opencam

using namespace opencam;

// ---------------------------------------------------------------------- exports

extern "C" BOOL WINAPI DllMain(HINSTANCE, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        // Media Foundation must be initialised before any MF object is created in
        // the hosting process. It is reference counted, so a matching shutdown on
        // detach is safe.
        MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);
    } else if (reason == DLL_PROCESS_DETACH) {
        MFShutdown();
    }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    *ppv = nullptr;

    if (clsid != CLSID_OpenCamVirtualCam) return CLASS_E_CLASSNOTAVAILABLE;

    auto* factory = new (std::nothrow) OpenCamClassFactory();
    if (!factory) return E_OUTOFMEMORY;

    const HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

STDAPI DllCanUnloadNow() {
    return _locks == 0 ? S_OK : S_FALSE;
}

/// Registration writes the COM record under HKLM, because the media source is
/// loaded by several processes including the Windows frame server services.
STDAPI DllRegisterServer() {
    wchar_t modulePath[MAX_PATH]{};
    if (!GetModuleFileNameW(nullptr, modulePath, MAX_PATH)) return HRESULT_FROM_WIN32(GetLastError());

    wchar_t clsidString[64]{};
    StringFromGUID2(CLSID_OpenCamVirtualCam, clsidString, ARRAYSIZE(clsidString));

    const wchar_t* keyPath = L"SOFTWARE\\Classes\\CLSID\\";
    std::wstring base = std::wstring(keyPath) + clsidString;

    HKEY key = nullptr;
    LONG result = RegCreateKeyExW(HKEY_LOCAL_MACHINE, base.c_str(), 0, nullptr,
                                  REG_OPTION_NON_VOLATILE, KEY_WRITE, nullptr, &key, nullptr);
    if (result != ERROR_SUCCESS) return HRESULT_FROM_WIN32(result);

    const wchar_t* description = L"OpenCam Virtual Camera";
    RegSetValueExW(key, nullptr, 0, REG_SZ, reinterpret_cast<const BYTE*>(description),
                   static_cast<DWORD>((wcslen(description) + 1) * sizeof(wchar_t)));

    HKEY inproc = nullptr;
    std::wstring inprocPath = base + L"\\InprocServer32";
    result = RegCreateKeyExW(HKEY_LOCAL_MACHINE, inprocPath.c_str(), 0, nullptr,
                             REG_OPTION_NON_VOLATILE, KEY_WRITE, nullptr, &inproc, nullptr);
    if (result == ERROR_SUCCESS) {
        RegSetValueExW(inproc, nullptr, 0, REG_SZ, reinterpret_cast<const BYTE*>(modulePath),
                       static_cast<DWORD>((wcslen(modulePath) + 1) * sizeof(wchar_t)));

        const wchar_t* threadingModel = L"Both";
        RegSetValueExW(inproc, L"ThreadingModel", 0, REG_SZ,
                       reinterpret_cast<const BYTE*>(threadingModel),
                       static_cast<DWORD>((wcslen(threadingModel) + 1) * sizeof(wchar_t)));
        RegCloseKey(inproc);
    }
    RegCloseKey(key);

    return result == ERROR_SUCCESS ? S_OK : HRESULT_FROM_WIN32(result);
}

STDAPI DllUnregisterServer() {
    wchar_t clsidString[64]{};
    StringFromGUID2(CLSID_OpenCamVirtualCam, clsidString, ARRAYSIZE(clsidString));

    // SHDeleteKey removes the whole subtree, InprocServer32 included.
    std::wstring base = std::wstring(L"SOFTWARE\\Classes\\CLSID\\") + clsidString;
    SHDeleteKeyW(HKEY_LOCAL_MACHINE, base.c_str());
    return S_OK;
}
