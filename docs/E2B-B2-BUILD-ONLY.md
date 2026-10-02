# B2 build-only follow-up

The parent authorized building and inspecting candidate APKs after the source-only
preparation at `8042ab717e725adf75ff01e025ffc3dbed0100c4`. Java fixture behavior
remains unchanged. This follow-up adds only an exact-branch build workflow and a
collector for actual compiled APK evidence. The Java-only lab also excludes AGP's
automatic unused Kotlin runtime from its own configurations. Ordinary app, B1 and native source stay
unchanged. No PR or ordinary Android workflow is dispatched.

The dedicated push job uses existing Java17/platform36/build-tools36 tools, verifies
the checksum-pinned wrapper, then invokes only `:ownerDeathLab:assembleDebug`,
`:ownerDeathLab:assembleDebugAndroidTest`, `:ownerDeathLab:lintDebug` and the same
module's `tasks --all`. It installs no APK, starts no emulator, calls no device bridge,
and injects no process exit. Missing existing SDK inspection tools stop the job.

The collector reads both APK binary manifests, badging, signatures, alignment and
DEX/native entries. It validates package/runner/SDK identity, each target service's
own private process and exported=false, no added permission or network capability,
no other component, no shared UID and no native library. Actual release task listing
must exclude release tasks. Build logs must contain no ordinary app task execution.
APK bytes, common signing certificate, source SHA/tree and every inspection report
are frozen in `candidate.json` and retained with the APKs. Source XML is not used as
a substitute for actual compiled manifest inspection.

Runtime is still a separate gate. A disabled proposal outside the active workflow
tree will consume the exact saved artifact without rebuilding, run P7/P8/P9 once
each only after approval, stop on first failure, and prove shutdown/deletion of its
own temporary emulator environment. The proposed fault budget remains exactly two
owner self-exits and one worker self-exit through exact fixture control. Until the
parent obtains concrete runtime approval, installation, instrumentation, device
bridge calls, emulator operations and injected process exits remain unexecuted.
