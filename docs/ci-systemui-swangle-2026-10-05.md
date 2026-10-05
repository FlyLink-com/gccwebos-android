# API36 graphics-backend comparison

Risk B: this changes only the owned disposable M0 emulator's host GLES backend
from legacy SwiftShader to SwANGLE. Application and native-library code are
unchanged. It follows the approved SystemUI diagnosis on `8a172ea`.

[M0 run 37283927203](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37283927203/job/111678221803)
captured the original SystemUI PID 908 ANR at 08:37:40.971 UTC. Its main thread
was blocked in `SurfaceControl.nativeGetGPUContextPriority` during Application
initialization; the outgoing Binder transaction to SurfaceFlinger had waited
14551 ms. This identifies the client wait but not its service-side cause.

Simply renaming `swiftshader_indirect` to `swiftshader` would keep the existing
backend. SwANGLE instead uses ANGLE with SwiftShader software drivers, as listed
in the [official emulator graphics documentation](https://developer.android.com/studio/run/emulator-acceleration).
The comparison tests whether this different host GLES implementation removes
the startup stall; it is not a claim that the previous driver's root cause is known.

The only runtime option changed is `-gpu swangle`. Keep API36, the original
Pixel7 1080x2400 / 420 dpi display, GLES 3 support, HWUI skiagl,
disabled guest Vulkan, four host CPUs, the single warm boot, all ANR/foreground
checks, the 45-minute job limit and shared instrumentation deadline.

Unlike the prior fixed SwiftShader 3.0 identity check, SwANGLE's GLES minor
version is obtained dynamically from the host backend. The revised contract
accepts only guest 3.0/3.1/3.2 (196608/196609/196610) and requires the underlying
ANGLE version in the last host Graphics API Version to match that same minor. Unknown versions, GLES 2.x,
host/guest mismatches and later contradictory versions fail. This changes the
expected renderer identity/capability validation, not the emulator's API level
or its configured graphics version. Public source was inspected through the
[37.2.10 branch](https://android.googlesource.com/platform/external/qemu/+/f0c183f1cc7456ecd6f3607f2f47893768ae4334/android/android-emu/android/opengles.cpp#662);
the actual 37.2.12 binary still requires runtime readback validation.

Update the host identity check to require the actual selected GLES mode
`swangle`, ANGLE and SwiftShader adapter identity. Reject missing identity,
fallback to the old renderer or another backend, and later contradictory
selection/adapter logs. Existing guest Vulkan emulation initialization
rejections remain intact; ANGLE's internal host implementation is separate
from exposing Vulkan to the guest.

Nine offline renderer tests cover both accepted disable markers, incorrect or
missing guest properties, changed display dimensions, Vulkan initialization,
missing host identity and backend fallback. Before the implementation change,
the backend tests produced three failures (two valid SwANGLE cases were rejected,
and fallback to the legacy adapter was wrongly accepted). The version-pairing
tests also failed first on 3.1/3.2 and a contradictory late version. After the
fix, all nine pass. Hosted validation is still required to verify the actual
backend and complete application tests.

The first hosted comparison on `aa71f6f`,
[run 37292144779](https://github.com/gccwebos-com/gccwebos-android/actions/runs/37292144779/job/111704788507),
stopped at the first-boot renderer check, before the warm boot or APK installation.
The actual selected backend is SwANGLE with ANGLE/SwiftShader. The first-boot
log contains no ANR marker, its focused window is Launcher, and the screenshot
shows a normal home screen. This is preliminary startup evidence, not completed
warm-boot or application acceptance. All 1001 JVM tests pass without failures or
skips; Lint has zero errors.

The failure was in the new verifier: the real binary reports
`OpenGL ES 3.0 (OpenGL ES 3.1.0 (ANGLE 2.1.17841 git hash: 5fa2e61da947))`
and guest `196609`. The outer translator version is not the nested underlying
ANGLE driver version. Public gfxstream source
[constructs that pair explicitly](https://android.googlesource.com/platform/hardware/google/gfxstream/+/d047a57228332d995d36600792fa9ccc26cf8ae6/host/gl/glestranslator/GLcommon/GLEScontext.cpp#2066);
this explains the string shape but is not an exact 37.2.12 build provenance claim.
Inner/guest equality is this CI's bounded acceptance contract, not a general
Android requirement for every renderer. The initial checker compared the guest to the outer 3.0
and rejected this valid pairing. The follow-up requires the complete nested
ANGLE format and matches its inner version to the guest; malformed/unrecognized
formats, mismatches and later conflicting version records still fail closed.
The regression fixture now uses the actual adapter/version strings. It produced
eight failing assertions with the prior checker; all ten test methods pass after
the correction, and the real first-boot artifact passes offline replay. No runtime
emulator option or other acceptance gate changes in this correction. A new hosted
run is required for the warm boot and complete application tests.

Rollback: restore `-gpu swiftshader_indirect` and its matching host-identity
checks. Preserve the ANR diagnostics and all acceptance gates.
