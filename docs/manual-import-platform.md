# Manual import input platform

The approved import UI supplies three explicit entries: scan, configuration file and
subscription link. Reading or scanning only stages bytes. The user must press Import;
the account/controller coordinator still owns source validation, cleanup, atomic adoption,
generation/clear/source-revision checks and every connection decision.

`manualimport/ManualImportHost.kt` accepts an opaque ticket from `onBegin`. The ticket
accompanies the eventual submission or fixed failure. `onSubmit` transfers ownership of
the input byte array; the receiver must copy or consume it synchronously and erase its
owned buffers in `finally`. The host erases staged buffers on dismissal or disposal and
calls `onCancel`. Late callbacks cannot target a new host/ticket. Busy disables submission;
there is no spinner, automatic retry, automatic source switch or VPN start.

The link field uses ordinary `remember`, never SavedState, and a secure dialog window.
QR/file content is never rendered. Source removal requires a separate confirmation;
using the account subscription is a separate explicit action.

## Packaged scanner

Pinned dependencies are `com.journeyapps:zxing-android-embedded:4.3.0` and its upstream
default `com.google.zxing:core:3.4.1`. The tagged upstream build declares that Core version.
The app removes the library's generic CaptureActivity registration and declares its own
non-exported `ManualQrScannerActivity`. CAMERA is requested only after selecting Scan.
No GMS, external scanner, external image capture, model download or remote decoder is used.

### Scanning a QR from a photo (相册)

The scanner screen has a 「从相册选择图片」 button. It opens the system Photo Picker
(`ActivityResultContracts.PickVisualMedia.ImageOnly`, no storage permission; the app receives only the one image the user picks).
The image is read into memory (at most 32 MiB, `content://` only), decoded with BitmapFactory sampling to at most 2048 px per side
and then decoded by the same ZXing QR reader as the camera: as is, inverted (light-on-dark screenshots), then 2x/4x box-downscaled.
Exactly one code is accepted; zero or several are reported, never guessed. The text then takes the same path as a camera result
(`ManualInputBytes.fromText` -> private mailbox -> host confirmation), so every downstream trust check is unchanged.
Nothing is written to disk, the clipboard is never read, nothing is logged, and every buffer holding image data is wiped.
Messages: 图片中没有识别到二维码 / 图片中有多个二维码 / 图片无法读取. The screen keeps `FLAG_SECURE`. Camera permission is still requested first
(a denial still ends the scan with the fixed permission failure).

The scanner uses `BarcodeView` with a QR-only decoder. Multiple detected QR results in the
same decoder frame are rejected. It never reads a result bitmap or writes a camera image.
FLAG_SECURE prevents ordinary window/recents capture. The result Intent contains only a
random receipt identifier or a fixed error enum. Decoded bytes stay in a process-only,
one-shot mailbox; cancellation wipes retained bytes. Missing mailbox state after process
death and Activity recreation cancel the scan rather than resuming a secret result.
The camera is paused on Activity pause and released on destruction. Denied permission,
absent/broken camera, Back and invalid input map to fixed messages without exception text.

The app can erase arrays it owns, but cannot promise erasure of immutable input strings,
Android camera buffers or decoder-internal copies.

Sources: [tagged usage and lifecycle guidance](https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0),
[tagged dependency versions](https://github.com/journeyapps/zxing-android-embedded/blob/v4.3.0/build.gradle),
[BarcodeView lifecycle](https://github.com/journeyapps/zxing-android-embedded/blob/v4.3.0/zxing-android-embedded/src/com/journeyapps/barcodescanner/BarcodeView.java).
Upstream license texts and ZXing Core NOTICE are in `app/src/main/assets/licenses`.

## Document boundary

SAF requests one `ACTION_OPEN_DOCUMENT` / `CATEGORY_OPENABLE` document with a read grant.
No tree, broad storage permission, write grant or persistable grant is requested. A canceled
picker retains its callback slot until Android returns, so a late old result cannot be
reassigned to a newer import. Only `content://` results are read. The adapter does not trust
MIME, extension, display name, `available()` or provider-reported size: it reads at most
65,537 bytes, rejects anything above 65,536 bytes, and strictly checks UTF-8. Cancellation
signals the provider and closes the owned stream. The original file remains untouched.

The UI/platform boundary does not broaden the official URL allowlist, classify authority,
invent expiry or validate only a selected YAML node. Those checks remain in the shared
import/authority implementation. [Android SAF guidance](https://developer.android.com/training/data-storage/shared/documents-files)
documents user-selected document access without broad storage permission.

## Validation status

Recorded validation on 2026-10-04 local / 2026-10-05 UTC is under
`.build-output/manual-import-oct4/`. The complete scope and artifact boundaries are in
[manual-import-validation.md](manual-import-validation.md).

- The focused JVM run passed all 170 tests, including four input-boundary tests, three
  actual packaged QR encoder/decoder tests and ten import-controller boundary tests.
  Coverage includes strict size/UTF-8 checks, ambiguous QR frames, late/canceled inputs,
  queued cancellation, source replacement and commit admission.
- A subsequent account-source switching fix passed the expanded focused run, 172/172,
  after two new tests reproduced the old missing-node result. The final APK passed seven
  Host/account UI cases again. The larger 32-case batch and four physical paths below
  belong to the preceding snapshot; they were not rerun for that controller-only change.
- Debug app/test builds passed. `zxing-resolved.log` confirms Android Embedded 4.3.0 and
  Core 3.4.1. Lint completed with zero errors, 31 warnings and six hints.
- OPPO API 33 isolated UI validation passed **32/32** in `manual-ui-final.json`. This includes
  Host confirmation/cancellation/recreation, document Intent and private-scanner contracts,
  account foreground recovery and source/usage isolation, encrypted sidecar and original v1
  storage, selected-node recreation, wallet resume and the website card.

Real platform paths used the packaged Host, ActivityResult, scanner and SAF, without a
platform override. Each following unique path passed one explicit device test:

| Path | Passing receipt | Verified result |
| --- | --- | --- |
| SAF YAML document | `manual-document.json` | Explicit confirmation, one local source commit, no download or invented expiry |
| SAF URL document | `manual-document-link.json` | Explicit confirmation, one remote source commit, one injected download and its own expiry |
| Physical camera QR | `manual-camera-controlled.json` | Actual decode, staged URL, explicit confirmation and one remote source commit |
| Camera permission denied | `manual-camera-denied-automated.json` | Fixed permission failure, zero submission/download/commit, source remains None |

`manual-camera-denied-final.json` also passed the same denial path; it is a repeat, not a
fifth unique path. The earlier negative UI, camera and permission attempts remain recorded
separately and are not counted as passing evidence. The initial UI assertions were corrected
for merged node-card semantics and the shared connection/preparation button tag; the final
test still exercises the button and verifies no source adoption, borrowed expiry or VPN start.

Isolated baseline seed, candidate upgrade/import and old-APK rollback each passed their
explicit test; original synthetic v1 ciphertext remained identical. The full Windows JVM run
was **991 pass / 8 fail out of 999**; its POSIX file-permission failures remain documented in
the validation report and do not constitute a fully green suite.

All passing physical import fixtures used fixed owned synthetic inputs and an injected
downloader. They establish camera/SAF and permission behavior, not real subscription HTTP or
tunnel forwarding. Host recreation coverage does not establish every physical-camera
process-death or hardware-failure scenario. Normal-app migration and the existing B2 P7
recovery gate remain outside this platform acceptance.
