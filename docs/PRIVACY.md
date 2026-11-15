# Anvil Privacy Policy

_Last updated: 15 November 2026_

Anvil ("the app") is developed by Ruchin Audichya. This policy explains what the app does with data.

## What Anvil collects

**Nothing.** Anvil has no accounts, no analytics, no advertising and no crash-reporting SDKs. The developer does not receive any data from your device.

## Network use

Anvil connects to the internet only when you choose to download a Windows or Linux image:

- **Windows images** are downloaded directly from Microsoft's official servers (`microsoft.com`). Microsoft receives the standard information any web request includes (such as your IP address) and may run its own download-session checks. Microsoft's handling of that data is governed by the [Microsoft Privacy Statement](https://privacy.microsoft.com/privacystatement).
- **Linux images** are downloaded from the distribution's official servers and mirrors (for example `releases.ubuntu.com`, `cdimage.debian.org`, `dl.fedoraproject.org` and the mirrors listed in the catalog), under those projects' own policies. To pick the fastest mirror, Anvil requests the first 128 KiB of the image from each listed mirror.
- **The Linux catalog** (which distros and mirrors are offered) is fetched from this project's GitHub repository (`raw.githubusercontent.com`) when you open "Download Linux". If that fails, the copy built into the app is used. GitHub's handling of that request is governed by the [GitHub Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

## Data on your device

- Disk images you pick or download stay on your phone; Anvil only reads them to write your USB drive.
- If you add a drivers folder to a Windows USB drive, Anvil reads that folder only to copy it onto the drive.
- If you enable Windows Setup customisation, the username/password you type are written **only** into `autounattend.xml` on the USB drive you are creating. They are never sent anywhere.
- Anvil writes to the USB drive you select, after you confirm. All data on that drive is erased.

## Permissions

- **USB host:** to talk to the USB drive you connect.
- **Internet / network state:** to download images you request.
- **Foreground service, wake lock, notifications:** to keep long writes running and show progress.

## Children

Anvil is a general-purpose utility and is not directed at children.

## Contact

Questions: ruchinaudichya09@gmail.com · https://www.ruchinaudichya.in
