## Anvil 0.5.0: Linux, properly

**Download:** [ruchinaudichya.in/anvil](https://ruchinaudichya.in/anvil), or `Anvil.apk` below.
Installs over 0.4.0 as a normal update.

Tested on real phones and USB drives against the
[real-device checklist](https://github.com/Ruchin-Audichya/anvil-usb/blob/main/REAL-DEVICE-TEST.md).
Reports from your phone and USB drive are still very welcome.

### What's new

**Download Linux, from official sources, verified**
- Pick **Ubuntu** (26.04 LTS or 26.10), **Linux Mint** 22.3 (Cinnamon or Xfce), **Fedora Workstation** 45 or
  **Debian** 13 (netinst or Live GNOME). Anvil downloads the official ISO and writes it, all from the phone.
- Before downloading, Anvil fetches the distro's checksum list and checks its **GPG signature with the
  distro's key, pinned inside the app**. Then it checks the ISO's **SHA-256**. You see a green
  **Signature + checksum verified** before you can write. If either check fails, nothing is written.
- **Fastest mirror:** every mirror gets a quick test download; Anvil uses the fastest and switches
  automatically if it fails. Downloads resume after network drops and app restarts.
- The catalog updates from this repository, so new point releases appear without an app update.
- Thanks to Ishan Jarwal for asking for this on LinkedIn.

**Windows: "No drivers were found"**
- New **Add drivers folder** option on the Windows write screen: pick your laptop's Intel RST/VMD storage
  driver folder and Anvil copies it to `\Drivers` on the USB drive.
- After writing, a help card explains the fix: set SATA mode to AHCI or disable VMD in the BIOS, or in Setup
  tap **Load driver → Browse → \Drivers**.

**More reliable on fussy hardware**
- **Settings → Compatibility mode** uses 16 KiB USB transfers (and 64 KiB SCSI commands). It's slower, but it
  helps with phones, OTG hubs and USB drives that disconnect or fail mid-write.

**New look**
- New anvil icon with a gradient steel body and spark, Android 13+ themed (monochrome) icon, and a matching
  notification icon.

Everything new is in English and हिन्दी.

### Installing

1. On your phone, open **ruchinaudichya.in/anvil** and tap **Download APK** (or download `Anvil.apk` below).
2. Open the file and allow **Install unknown apps** for your browser when Android asks.
3. If installing is blocked (Anvil isn't on the Play Store yet, so Android double-checks it):
   - Chrome says *"File might be harmful"* → tap **Download anyway**.
   - Play Protect says *"Unsafe app blocked"* or *"Scan app"* → tap **More details → Install anyway** (or scan, then install).
   - Samsung: if nothing happens or it says *"Blocked by Auto Blocker"*, turn off
     **Settings → Security and privacy → Auto Blocker** while installing, then turn it back on.

**Updating from 0.4.0:** install over the top. **From v0.3.x:** uninstall the old version once first
(0.4.0 changed the signing key).

### Verify the download

| | |
|---|---|
| File | `Anvil.apk` (0.5.0, versionCode 6) |
| Size | 16,709,620 bytes (15.9 MiB) |
| SHA-256 | `7452e25a8c1407591c984ac8305120d09419b58170d7ca609b20946624b3244f` |
| Signing certificate SHA-256 | `f9e5689d51617d5f67f73537537ab43b96907751d88011d705827080e4b93a3e` (same as 0.4.0) |

### Notes

- The APK is about 3 MB larger than 0.4.0, mostly Bouncy Castle (the OpenPGP library used for signature checks).
- New Fedora releases need an app update for their signing key (Fedora 44–46 are included).
