# Anvil — Architecture

## Module layout

```
anvil/
├── app/                      Android app (Compose UI, Hilt, foreground service, Android USB transport)
│   ├── usb/                  AndroidUsbTransport (UsbDeviceConnection), UsbDriveRepository (attach/detach/permission)
│   ├── jobs/                 JobManager (single long-running job, StateFlow) + TransferService (FGS + notification)
│   ├── source/               ContentUriImageSource (SAF picker → ImageSource)
│   └── ui/                   home / write / windows screens, shared TransferProgressCard
├── core/common      (JVM)    errors with human messages, ByteSize, TransferMeter (speed/ETA), SHA-256 helpers, DebugLog
├── core/blockdev    (JVM)    BlockDevice, USB Bulk-Only Transport, SCSI (SBC) commands, ScsiBlockDevice, MemoryBlockDevice
├── core/image       (JVM)    ImageInspector: hybrid ISO vs Windows UDF ISO vs disk image
├── core/writer      (JVM)    RawImageWriter (dd-style, pipelined, read-back verify)
├── core/download    (JVM)    ResumableDownloader (Range/If-Range, retries, signed-URL refresh, SHA-256)
├── core/catalog     (JVM)    MicrosoftIsoResolver (official Windows links + hashes); Linux catalog:
│                             manifest, pinned distro keys, OpenPGP checks (Bouncy Castle), mirror probing
├── core/udf         (JVM)    read-only UDF reader (Windows ISOs keep their real file tree in UDF)
├── core/wim         (JVM)    WimSplitter: install.wim → .swm parts, compressed resources copied verbatim
├── core/fat         (JVM)    GPT/MBR partitioner + single-pass FAT32 writer (contiguous runs, generated FAT)
└── tools/devcli     (JVM)    host CLI: download-windows, inspect-iso, write-windows (to an image file)
```

All core modules are plain Kotlin/JVM, so the entire USB protocol stack, writer and
downloader are unit-tested on a laptop (`./gradlew test`) against simulated devices
and a mock HTTP server — no phone needed for most development.

Planned additions, by milestone:

| Milestone | New modules |
|---|---|
| 2 Windows writer | done: `core:udf`, `core:wim`, `core:fat` (pure Kotlin, no NDK needed) |
| 3–4 Catalog | done (0.5.0): `core:catalog` Linux manifest + mirrors + pinned-key GPG verification (Bouncy Castle) |
| 5 Stream to USB | `core:writer` gets an HTTP `ImageSource` with a ring buffer and backpressure |
| 6 Customisation | done: `core:unattend` + Customize card in the write screen |
| 7 Rescue | `feature:rescue`, `feature:guide`, offline content as bundled assets |
| 8 Polish | Room (`data:history`), DataStore (`data:settings`), Baseline Profiles |

UI features move into `feature:*` Android library modules once they grow beyond one screen.

## The three riskiest parts

### 1. Raw USB block access without root
Android normally mounts a USB stick through vold (FAT/exFAT only) and gives apps
file-level SAF access — never raw sectors. Anvil bypasses the kernel driver
entirely:

1. Find the interface with class 8 / subclass 6 / protocol 0x50 (Mass Storage, SCSI, Bulk-Only).
2. `UsbManager.requestPermission()` → `openDevice()` → `claimInterface(intf, force = true)`.
   `force` detaches the kernel `usb-storage` driver, so vold unmounts the stick.
3. Speak USB Mass Storage Bulk-Only Transport ourselves: 31-byte CBW → data → 13-byte CSW,
   wrapping SCSI commands (INQUIRY, TEST UNIT READY, READ CAPACITY(10/16), MODE SENSE for
   write-protect, READ/WRITE(10/16), SYNCHRONIZE CACHE, REQUEST SENSE).
4. Error recovery per the BOT spec: on a STALL, clear the endpoint halt and read the CSW; on a phase
   error or bad CSW do a Bulk-Only Mass Storage Reset then clear both halts. Commands are retried with
   REQUEST SENSE (UNIT ATTENTION after power-up is normal). A detached device surfaces as
   `DeviceDisconnectedException`, and the UI shows recovery instructions.

Quirks handled: `bulkTransfer` silently capped at 16 KiB before Android 9 (we chunk); sticks that
don't implement SYNCHRONIZE CACHE or MODE SENSE; descriptor-format sense data; drives over 2 TiB
(16-byte CDBs). Fake-capacity drives get caught by the read-back SHA-256 verification.
`FakeUsbStick` (test) implements the device side of the protocol, so the exact bytes are tested.

Remaining risk: some OEM kernels and cheap hubs. Mitigation: the "Compatibility mode" setting (0.5.0, `UsbTuning`)
uses 16 KiB bulk transfers and 64 KiB SCSI commands instead of 128 KiB / 1 MiB. An async `UsbRequest` queue for
throughput is still to do.

### 2. WIM splitting (milestone 2) — DONE, without the NDK
Implemented in pure Kotlin (`core:wim`) instead of binding wimlib: a split never decompresses, it only
re-packages compressed resources into parts with new headers and blob tables, exactly as wimlib's
`split.c` does. Output is verified by wimlib itself (`WimSplitterTest`, and on the real Win11 image).
Solid (ESD/LZMS) images can't be split by anyone; they need the NTFS layout (still to do).
Original plan, kept for reference:
Windows ISOs now ship `sources/install.wim` > 4 GiB, which FAT32 can't store, and UEFI firmware can only
boot from FAT32. Options:
- **Primary:** split into `install.swm`, `install2.swm`, … Setup accepts them natively. Splitting a WIM
  isn't a byte cut: each part needs a valid header, its own resource table, and the XML/metadata
  resources in part 1. We'll build **wimlib** (LGPLv3) for arm64-v8a/armeabi-v7a/x86_64 with the NDK
  as a shared library (`libwim.so`, dynamic linking keeps the app LGPL-compliant) and call
  `wimlib_split()` through a small JNI shim. The input is streamed from the ISO's UDF filesystem via a
  custom `wimlib` read callback (no temp copy of the 6 GB file); parts go straight into the FAT32 writer.
- **Alternative (Advanced):** NTFS/exFAT data partition + a small FAT partition with the UEFI:NTFS
  bootloader (Rufus approach). No split needed, but Secure Boot must accept the UEFI:NTFS signature.
- FAT32 formatting and file writing is our own code over `BlockDevice` (simple, testable,
  no filesystem driver needed); GPT + protective MBR likewise.

### 3. Fetching official Windows links (done in this milestone)
Microsoft has no public API, only the web page's own JSON endpoints. Verified live (Nov 2026):
1. `GET https://www.microsoft.com/en-us/software-download/windows11` (non-Windows User-Agent, otherwise
   the Windows 10 page serves the Media Creation Tool) → product edition id from
   `<option value="3813">` and the "Verify your download" SHA-256 table.
2. Session id = random UUID v4.
3. `GET https://ov-df.microsoft.com/mdt.js?instanceId=560dc9f3-…&pageId=si&session_id=<id>` returns
   a beacon URL. We request it (`&mdt=<ms>&rticks=<n>`) so Microsoft's anti-abuse layer registers the session.
4. `GET /software-download-connector/api/getskuinformationbyproductedition?profile=606624d44113&ProductEditionId=3813&…&sessionID=<id>` → languages + SKU ids.
5. `GET /software-download-connector/api/GetProductDownloadLinksBySku?…&SKU=<sku>&sessionID=<id>` with
   `Referer` → signed `software.download.prss.microsoft.com` URL, valid 24 h, supports HTTP Range.

Risks: Microsoft changes markup or ids (edition ids change every release, so we scrape them rather than
hard-code), or rate-limits / geo-blocks (error `715-123130`, "Sentinel"), common on VPNs.
Mitigations: all parsing lives in `MicrosoftPageParser` with tests; the sentinel error gets a
human-readable explanation; the downloader refreshes expired URLs mid-download; a remote manifest
(milestone 4) can override the profile id or ov-df instance id without an app update; and
`./gradlew :core:catalog:liveTest` checks the live flow (run nightly in CI).

## Threading & lifecycle
`JobManager` (singleton) runs one job at a time in its own `CoroutineScope`. `TransferService`
(foreground; `connectedDevice` type for writes, `dataSync` for downloads) holds a partial wake lock and
mirrors `JobState` to a notification with Cancel. The UI only observes `StateFlow`s. WorkManager will
take over download scheduling when auto-resume-on-Wi-Fi lands (milestone 3).

## Linux catalog (0.5.0)

```
linux-manifest.json (remote from the repo, else bundled in assets)
   └─ entry: mirrors[], isoPath "26.04/ubuntu-{version}-desktop-amd64.iso", checksumUrl, signatureUrl?, keyId
        1. fetch SHA256SUMS (+ .gpg / .sign) from the first mirror that serves it
        2. verify with the key pinned in the app for keyId  ── fail → stop, nothing downloaded or written
        3. pick the newest {version} match from the *signed* text → file name + SHA-256
        4. ranged GET (128 KiB) on every mirror in parallel → 206-capable mirrors, fastest first
        5. ResumableDownloader: Range/If-Range resume, switches mirror after 3 failures, verifies SHA-256
```

- **Trust lives in the app, not the manifest.** The manifest can only name a `keyId`; the key files ship in
  `core/catalog/src/main/resources/.../keys/` and must match fingerprints hard-coded in `PinnedKeys`
  (checked against each distro's own verification page). Remote entries naming unknown keys or plain-http
  mirrors are dropped. A new Fedora release key needs an app update; Fedora 44–46 are pinned.
- **Clear-signed files** (Fedora's `CHECKSUM`) are parsed only from the text the signature covers.
- **`{version}`** matches digits, dots and dashes, so `ubuntu-{version}-desktop-amd64.iso` picks `26.04.1`
  over `26.04` and skips `26.10-beta`; Debian's `current/` paths keep working across point releases.
- Tests use the real published checksum files and signatures as fixtures (kept byte-exact by
  `.gitattributes`). `./gradlew :core:catalog:liveTest` resolves every bundled entry against the real
  mirrors and prints a `MIRROR FAILURES` block for pruning.
