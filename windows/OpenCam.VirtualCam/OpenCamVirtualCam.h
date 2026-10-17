#pragma once

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mferror.h>
#include <shlwapi.h>

// {6F1B4C93-7A2E-4D71-9C58-2E4A0B7D5F31}
// Must stay in step with VirtualCameraIds.Clsid in the C# client.
//
// Declared rather than defined here so this translation unit does not pull in
// initguid.h, which would define every Media Foundation GUID and collide with
// mfuuid.lib at link time.
extern const GUID CLSID_OpenCamVirtualCam;

namespace opencam {

/// Name of the named shared memory section the client publishes frames into.
/// Must match SharedFrameBuffer in the C# client.
constexpr wchar_t kSharedSectionName[] = L"OpenCamFrameBuffer";

/// 'OCAM'
constexpr DWORD kFrameMagic = 0x4F43414D;

/// Layout version of the shared header.
constexpr DWORD kFrameLayoutVersion = 1;

/// BGRA32 pixels.
constexpr DWORD kPixelFormatBgra32 = 1;

/// Matching SharedFrameBuffer in the C# client.
#pragma pack(push, 1)
struct OpenCamFrameHeader {
    LONGLONG sequence;      // seqlock: odd while the writer is mid-frame
    DWORD    magic;
    DWORD    version;
    INT32    width;
    INT32    height;
    INT32    stride;        // bytes per row
    INT32    pixelFormat;
    INT32    fps;
    INT32    reserved;
    LONGLONG frameCounter;  // bumped once per published frame
    LONGLONG timestampMs;   // unix epoch milliseconds
};
#pragma pack(pop)

static_assert(sizeof(OpenCamFrameHeader) == 56, "shared header layout drifted");

/// Largest frame the section can hold. Must match SharedFrameBuffer.
constexpr int kMaxWidth = 1920;
constexpr int kMaxHeight = 1080;

/// Frames per second advertised to consumers.
constexpr UINT32 kDefaultFps = 30;

/// Output pixel format. Media Foundation stores RGB32 as BGRX, which matches the
/// BGRA byte layout the client writes (the alpha byte is ignored).
/// MFVideoFormat_RGB32 is an extern const GUID defined in mfapi.lib, so it is not
/// usable in a constant expression; hand it out by reference instead.
inline const GUID& OutputSubtype() { return MFVideoFormat_RGB32; }

} // namespace opencam
