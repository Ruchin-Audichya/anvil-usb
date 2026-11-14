# Galaxy Store listing: Anvil (galaxy flavor, v0.4.0)

> **Status (14 Nov 2026): on hold.** Samsung Seller Portal now requires a *corporate Commercial Distribution Seller*
> to register Android apps; individual sellers can't. Kept for when a registered business exists.

- **Package:** dev.ruchin.anvil
- **APK:** `app/build/outputs/apk/galaxy/release/app-galaxy-release.apk`
- **Category:** Tools (Utilities)
- **Price:** Free · **Ads:** None · **In-app purchases:** None
- **Age rating:** All ages (no user-generated content, no sharing, no location)
- **Privacy policy URL:** https://github.com/Ruchin-Audichya/anvil-usb/blob/main/docs/PRIVACY.md (live after the branch is merged/pushed)
- **Support email:** ruchinaudichya09@gmail.com
- **Icon:** `icon-512.png` (512×512)
- **Screenshots (1080×1351):** marketing/slides/slide-1,2,3,4,5,6,8,9.png. **Leave out slide-7** (shows the Windows 11 check-skip, which the Galaxy build hides)

## App name
Anvil: Bootable USB Maker

## Short description (≤ 80 chars)
Make bootable Windows & Linux USB drives with just your phone. No PC, no root.

## Full description
Your PC won't boot and you need an install USB, but there's no second computer around. Anvil turns your Android phone into a bootable-USB maker.

Plug a USB drive into your phone (OTG adapter or USB-C drive), pick an image, and Anvil writes a drive that boots on UEFI PCs. No PC. No root.

WHAT IT DOES
• Windows 10 & 11 installers: download the ISO straight from Microsoft's official servers inside the app, or pick one you already have
• Handles the 7+ GB install.wim: automatically split for FAT32 so the drive boots on UEFI with Secure Boot on
• Linux: write Ubuntu, Fedora, Mint and other hybrid ISOs directly
• Verifies every write with a SHA-256 read-back, so you know the drive is good before you walk away
• Optional Windows Setup helpers: create a local account, skip the privacy screens, keep BitLocker off
• Runs in the background with progress notifications

WHY IT WORKS WITHOUT ROOT
Android doesn't allow apps to write raw disks, so Anvil talks to the drive itself over the USB Mass Storage protocol, with its own GPT partition and FAT32 writers.

PRIVACY
No accounts, no ads, no analytics. Images are downloaded only from official sources when you ask.

NOTE
Writing erases everything on the selected USB drive. Anvil always asks you to confirm first.

Open source: github.com/Ruchin-Audichya/anvil-usb

## Tags / keywords
bootable usb, windows installer, linux usb, iso to usb, usb otg, rufus, pc repair, windows 11 install
