// Host side of the OpenCam virtual camera.
//
// Creating a virtual camera means calling MFCreateVirtualCamera and holding the
// resulting IMFVirtualCamera alive for as long as the camera should exist. That
// is a handful of COM calls, so it lives here, in the same binary as the media
// source, where the SDK headers describe the interfaces for the compiler. The
// C# client then only needs a plain P/Invoke surface with opaque handles.
//
// The media source itself (this DLL) must be registered under
// HKLM\SOFTWARE\Classes\CLSID\{...}\InprocServer32 before the frame server can
// load it; see register.bat. Until then MFCreateVirtualCamera or Start fails and
// the failure is reported to the caller unchanged.

#include "OpenCamVirtualCam.h"

#include <mfvirtualcamera.h>
#include <string>

#pragma comment(lib, "mfsensorgroup.lib")

namespace {

/// The lifetime of a camera created for the desktop client. Session lifetime
/// means Windows removes it automatically when the process exits, so an
/// interrupted OpenCam session cannot leave a phantom camera behind.
constexpr MFVirtualCameraLifetime kLifetime = MFVirtualCameraLifetime_Session;

/// Current user rather than all users: creating the camera needs no elevation,
/// and only the signed-in user sees it.
constexpr MFVirtualCameraAccess kAccess = MFVirtualCameraAccess_CurrentUser;

} // namespace

extern "C" {

/// True when this Windows build can host software camera sources at all. The API
/// arrived with Windows 11 (build 22000).
__declspec(dllexport) BOOL WINAPI OpenCamVirtualCameraIsSupported() {
    BOOL supported = FALSE;
    const HRESULT hr = MFIsVirtualCameraTypeSupported(
        MFVirtualCameraType_SoftwareCameraSource, &supported);
    return SUCCEEDED(hr) && supported ? TRUE : FALSE;
}

/// Creates and starts a virtual camera that streams this DLL's media source.
///
/// On success <paramref name="handle"/> receives an opaque pointer that must be
/// passed to OpenCamVirtualCameraRelease. On failure it stays null and the
/// returned HRESULT says why, which is what the client shows the user.
__declspec(dllexport) HRESULT WINAPI OpenCamVirtualCameraStart(
    const wchar_t* friendlyName, void** handle) {
    if (!handle) return E_POINTER;
    *handle = nullptr;

    wchar_t clsidString[64]{};
    if (!StringFromGUID2(CLSID_OpenCamVirtualCam, clsidString, ARRAYSIZE(clsidString)))
        return E_UNEXPECTED;

    const wchar_t* name = (friendlyName && *friendlyName) ? friendlyName : L"OpenCam";

    // The source id identifies the media source that backs the camera. For a
    // software camera source that is the media source's CLSID, in registry string
    // form.
    IMFVirtualCamera* camera = nullptr;
    HRESULT hr = MFCreateVirtualCamera(
        MFVirtualCameraType_SoftwareCameraSource,
        kLifetime,
        kAccess,
        name,
        clsidString,
        nullptr,
        0,
        &camera);
    if (FAILED(hr)) return hr;

    hr = camera->Start(nullptr);
    if (FAILED(hr)) {
        // A camera that failed to start must not linger in the device list.
        camera->Remove();
        camera->Release();
        return hr;
    }

    *handle = camera;
    return S_OK;
}

/// Stops the camera from producing frames while keeping the device in place.
__declspec(dllexport) HRESULT WINAPI OpenCamVirtualCameraStop(void* handle) {
    if (!handle) return E_POINTER;
    auto* camera = static_cast<IMFVirtualCamera*>(handle);
    return camera->Stop();
}

/// Stops, unregisters and releases a camera created by
/// OpenCamVirtualCameraStart. Safe to call with a null handle.
__declspec(dllexport) void WINAPI OpenCamVirtualCameraRelease(void* handle) {
    if (!handle) return;
    auto* camera = static_cast<IMFVirtualCamera*>(handle);
    camera->Stop();
    camera->Remove();
    camera->Release();
}

} // extern "C"
