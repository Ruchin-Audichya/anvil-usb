# Anvil 0.5.0: real-device test checklist

This checklist was run on real hardware before 0.5.0 was promoted from pre-release to the latest
release (16 November 2026). Run it again for each release before promoting it:

```bash
gh release edit vX.Y.Z --prerelease=false
```

Use at least **2 phones (one Samsung)** and **3 different USB drives** across the run. Note phone model,
Android version, drive model and any failure (tap **Copy debug log** on the failed card).

## 1. Install and upgrade

- [ ] Install the 0.5.0 `Anvil.apk` **over 0.4.0** (no uninstall). App data survives, version shows 0.5.0.
- [ ] Fresh install on a second phone through ruchinaudichya.in/anvil (Chrome "Download anyway",
      Play Protect "Install anyway", Samsung Auto Blocker off/on).
- [ ] New launcher icon looks right; with Android 13+ *Themed icons* on, the monochrome anvil shows.
- [ ] Notification icon (status bar, during a download) is the new anvil silhouette, not a white square.

## 2. Linux catalog (the "done when" for this release)

For each distro: Download Linux → pick → **Check signature & find fastest mirror** → Download → **Write this ISO
to a USB drive** → boot a **UEFI** PC from it.

- [ ] **Ubuntu 26.04 LTS** shows "Checksum list signed by Ubuntu CD Image Automatic Signing Key (2012)",
      downloads, card shows green **Signature + checksum verified**, writes, boots to the live desktop.
- [ ] Ubuntu latest (26.10): resolves and verifies (boot optional).
- [ ] **Linux Mint 22.3 Cinnamon**: download, verify, write, boot.
- [ ] **Fedora Workstation 45**: download, verify (clear-signed CHECKSUM), write, boot.
- [ ] **Debian 13 netinst** and **Debian Live GNOME**: download, verify, write, boot.
- [ ] Hindi UI (system language हिन्दी): every new screen and the verified badge read correctly.

## 3. Resume and failure handling

- [ ] Start an Ubuntu download, turn on **airplane mode** for 30 s, turn it off: it resumes, does not restart.
- [ ] Start a download, **force-stop the app**, reopen Download Linux, tap Download: it resumes from the
      partial file and still ends in "Signature + checksum verified".
- [ ] **Bad mirror:** block the fastest mirror's host (e.g. a DNS blocker app) mid-download: Anvil switches to
      the next mirror and finishes.
- [ ] **Bad checksum:** after a download, change one byte of the ISO in `Android/data/dev.ruchin.anvil/files/isos`
      and tap Verify downloaded ISO: it fails with a clear message, the file is deleted, no Write button.
- [ ] **Bad signature:** not reproducible on a phone without a test build; covered by unit tests
      (`LinuxVerificationTest`, `LinuxIsoResolverTest`). Confirm the message reads sensibly if you build one.
- [ ] No drive is ever written after any failure above.

## 4. Windows (regression + drivers folder)

- [ ] **Windows 11 flow unchanged:** download, write (GPT, customisation on), boot to Setup, install.
- [ ] **Add drivers folder:** turn on, pick a folder with an Intel RST/VMD driver (from the laptop maker's
      site), write. `\Drivers` exists on the stick with the same files.
- [ ] On an Intel 11th gen+ laptop with VMD on: Setup → Load driver → Browse → `\Drivers` finds the driver and
      the disk appears.
- [ ] The write-complete screen shows the "If Setup says No drivers were found" card (both variants: with
      and without a drivers folder).
- [ ] The write button stays disabled while "Add drivers folder" is on but no folder is picked.

## 5. Compatibility mode

- [ ] Settings → Compatibility mode on: write screen shows the notice; a Linux write completes (slower).
- [ ] On a phone/hub combination that previously failed mid-write (if you have one), compatibility mode helps.
- [ ] Turn it off again: full speed returns.

## 6. Sign-off

- [ ] All of the above on 2 phones (one Samsung) and 3 USB drives.
- [ ] Then: `gh release edit v0.5.0 --prerelease=false`, and reply to Ishan and the other commenters.
