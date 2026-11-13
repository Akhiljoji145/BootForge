# BootForge Android USB image writer — MVP

BootForge is an Android USB-OTG raw image writer.

## Implemented
- Android Storage Access Framework image picker
- USB mass-storage device discovery and Android USB permission
- USB Mass Storage Bulk-Only Transport (BOT)
- SCSI INQUIRY, READ CAPACITY, WRITE(10), SYNCHRONIZE CACHE
- Capacity check, progress, and explicit erase confirmation
- Android 12 target (SDK 31), Java 17 / Kotlin JVM 17

## Critical compatibility and safety limitations
- This is a raw disk-image writer, not a universal bootable USB installer creator.
- Hybrid Linux ISO images are the intended first use. Not every Linux ISO is hybrid or bootable when raw-written.
- Standard Windows installation ISO files generally cannot be made bootable by raw-writing the ISO bytes. Windows installer media requires partition/filesystem creation, UEFI/NTFS boot support and sometimes splitting install.wim. That separate Windows installer workflow is NOT implemented in this MVP.
- USB BOT compatibility varies by phone, Android firmware, hub, flash drive and controller. The implementation has not been validated across hardware. Use a spare USB drive only.
- Writing destroys all data and partitions on the selected USB drive. Verify the selected device and back up data first.
- A completed write is not a verified boot. Full read-back checksum validation and boot testing are not included.
- Some phones require a powered OTG hub.

## Build APK using GitHub Actions from a phone
1. Create a GitHub repository named BootForge.
2. Upload the ZIP contents preserving folders, including `.github/workflows/build-apk.yml`.
3. Open Actions → Build BootForge APK → Run workflow.
4. Download the BootForge-APK artifact after a successful run and extract `app-debug.apk`.

## Build configuration
Android target SDK 31, compile SDK 35, minimum SDK 23, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21.
