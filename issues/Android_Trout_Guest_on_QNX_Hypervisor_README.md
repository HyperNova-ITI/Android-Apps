# Android Trout Guest on QNX Hypervisor

## Architecture, Build, Image Packaging, and Deployment Plan

> **Project goal:** Build a custom Android Automotive OS guest for an NXP platform running a QNX Hypervisor host. The Android guest will be based on Google's ARM64 Trout reference product, but it will use a QNX-specific virtual-board configuration and NXP vendor components where real hardware is passed through.

---

## 1. Executive Summary

The plan is **not** to run Cuttlefish on the NXP board.

Cuttlefish is only useful as a development and testing environment on an x86_64 Linux workstation. The final target architecture is:

```text
NXP SoC
  |
  +-- QNX Host OS
       |
       +-- QNX Hypervisor / qvm
            |
            +-- HyperNova Android Automotive Guest
                 |
                 +-- Based on Android Trout ARM64
                 +-- VirtIO network
                 +-- Virtual USB controller
                 +-- GPU and display pass-through, if supported
                 +-- NXP Android vendor graphics stack
```

The correct strategy is:

```text
Google Trout ARM64 product
          |
          | inherit and customize
          v
HyperNova QNX Trout ARM64 product
          |
          | build
          v
Android guest boot files + Android guest disk
          |
          | supplied to qvm
          v
Android Automotive guest running on QNX
```

The final Android guest is therefore **a new product based on Trout**, not an unchanged copy of Trout and not an ordinary NXP Android image with only a modified kernel.

---

## 2. Current Project Status

The QNX Hypervisor platform is already able to boot a Linux guest.

The following host/guest functions have already been tested or partially tested:

- Linux guest boot on QNX Hypervisor
- Virtual USB support
- VirtIO network support, at least partially
- Other required Linux guest features

This proves that the basic QNX virtual-machine platform works:

```text
qvm
+ vCPU
+ guest RAM
+ virtual interrupt controller
+ virtual timer
+ serial console
+ virtual devices
```

The remaining problem is that Android is not a normal Linux distribution.

A Linux feature may work after enabling its kernel driver, while Android may require several additional userspace layers.

```text
Linux:

Kernel driver
  |
  v
Linux interface or device node
  |
  v
Linux userspace application
```

```text
Android:

Kernel driver
  |
  v
Device node / network interface
  |
  v
ueventd permissions
  |
  v
init service and properties
  |
  v
HAL or Android native service
  |
  v
VINTF declaration
  |
  v
SELinux policy
  |
  v
Android Framework
  |
  v
Application
```

This is the main reason why modifying only the Android kernel is not enough, especially for graphics, audio, vehicle integration, and some USB device classes.

---

## 3. Why Use Android Trout?

Android Trout is an Android Automotive reference platform designed to run as a virtualized IVI guest.

Google explicitly supports using Trout as the starting point for a new ARM64 IVI product:

```make
$(call inherit-product, device/google/trout/aosp_trout_arm64.mk)
```

Trout already provides useful Android-side architecture for:

- Android Automotive OS and CarService
- Virtualized Vehicle HAL design
- Host-to-guest communication through vsock and gRPC
- VirtIO-oriented kernel and product configuration
- Virtual networking integration
- Automotive audio configuration structure
- Android init services
- SELinux structure
- VINTF HAL declarations
- Dynamic partitions
- Guest-oriented Android configuration

Therefore, using Trout avoids re-creating a virtualized AAOS product from a normal physical-board Android product.

However, Trout is only a **reference starting point**.

The current Trout ARM64 product inherits significant Cuttlefish-specific configuration. Those parts must be replaced for QNX and NXP.

---

## 4. What Trout Provides and What Must Be Replaced

### 4.1 Components to Reuse from Trout

The project should reuse or extend:

```text
Android Automotive userspace
CarService
Automotive framework configuration
Virtualized VHAL architecture
vsock/gRPC communication model
Automotive product packages
Virtual-device SELinux policy structure
VINTF structure
init service structure
Dynamic partition structure
```

### 4.2 Components That Must Be Replaced

The project must replace or override:

```text
Cuttlefish BoardConfig
Cuttlefish kernel selection
Cuttlefish guest DTB
Cuttlefish boot properties
Cuttlefish fstab and block-device assumptions
Cuttlefish virtual graphics configuration
ranchu Graphics Composer
minigbm/Mesa settings when using NXP graphics
Cuttlefish host-side services
Cuttlefish disk assembly and launch flow
```

### 4.3 Components That Must Come from the NXP Android BSP

For physical devices passed directly to Android, Trout does not provide the real NXP hardware support.

For GPU/display pass-through, the project normally requires:

```text
NXP GPU kernel driver
NXP GPU firmware
NXP EGL libraries
NXP OpenGL ES libraries
NXP Vulkan libraries, when required
NXP Gralloc allocator HAL
NXP Graphics Composer HAL
NXP display configuration
NXP media or codec vendor components, when required
```

The Android guest is therefore built from three main technical sources:

```text
Google Trout
  -> virtualized AAOS product reference

NXP Android BSP
  -> real NXP hardware and vendor stack

QNX Hypervisor configuration
  -> guest virtual board and host backends
```

The HyperNova layer then adds applications, branding, overlays, and project-specific integration.

---

## 5. Final Software Layering

```text
+--------------------------------------------------+
| HyperNova Product Layer                          |
| Launcher, Media, Navigation, Phone, AI, overlays |
+---------------------------+----------------------+
                            |
+---------------------------v----------------------+
| Trout AAOS Virtualization Layer                  |
| CarService, virtual VHAL, vsock, AAOS structure  |
+---------------------------+----------------------+
                            |
+---------------------------v----------------------+
| NXP Android Vendor Layer                         |
| GPU, display, EGL, Gralloc, HWC, codecs          |
+---------------------------+----------------------+
                            |
+---------------------------v----------------------+
| QNX Android Guest Board Layer                    |
| Kernel, DTB, VirtIO, fstab, init, SELinux        |
+---------------------------+----------------------+
                            |
+---------------------------v----------------------+
| QNX Hypervisor Host                             |
| qvm, VirtIO backends, USB backend, pass-through  |
+--------------------------------------------------+
```

---

## 6. Product and Board Are Different Concepts

This distinction is critical.

### 6.1 Product Configuration

The product configuration selects:

- Android applications
- Android Automotive packages
- HAL service implementations
- overlays
- product properties
- feature configuration
- files copied into `system`, `system_ext`, `product`, and `vendor`

Example future product:

```text
hypernova_qnx_trout_arm64
```

### 6.2 Board Configuration

The board configuration selects:

- CPU architecture
- kernel
- kernel modules
- guest DTB
- boot image layout
- partition sizes
- fstab
- boot command line
- graphics stack
- device-specific vendor configuration

The new product may inherit Trout at the product level, but it still needs a new QNX/NXP board layer.

---

## 7. Proposed Device Tree Structure

A new device directory should be created without modifying Google's Trout source directly:

```text
device/hypernova/qnx_trout_arm64/
|
+-- AndroidProducts.mk
+-- hypernova_qnx_trout_arm64.mk
+-- BoardConfig.mk
+-- device.mk
|
+-- fstab.qnx_trout
+-- init.qnx_trout.rc
+-- ueventd.qnx_trout.rc
|
+-- manifest.xml
+-- compatibility_matrix.xml
+-- sepolicy/
|
+-- kernel/
|    +-- Image
|    +-- modules/
|
+-- guest_dtb/
|    +-- qnx-trout-guest.dts
|    +-- qnx-trout-guest.dtb
|
+-- graphics/
+-- overlays/
+-- tools/
     +-- guest image packaging tools
```

A simplified product file may begin like this:

```make
$(call inherit-product, device/google/trout/aosp_trout_arm64.mk)

PRODUCT_NAME := hypernova_qnx_trout_arm64
PRODUCT_DEVICE := qnx_trout_arm64
PRODUCT_BRAND := HyperNova
PRODUCT_MODEL := HyperNova Android Automotive Guest on QNX
PRODUCT_MANUFACTURER := HyperNova
```

This inheritance is only the starting point. Cuttlefish-specific packages and properties must then be removed, replaced, or overridden.

---

## 8. The Guest DTB Must Describe the Virtual Machine

The full physical NXP board DTB must **not** be passed directly to Android.

Android must receive a guest DTB that describes what QNX exposes inside the VM.

```text
Physical NXP board DTB
          !=
Android guest DTB
```

The guest DTB normally describes:

- guest CPUs
- guest RAM
- virtual interrupt controller
- virtual timer
- virtual UART
- VirtIO block devices
- VirtIO network
- virtual xHCI USB controller
- reserved memory
- physical devices passed through to Android
- GPU/display resources when passed through

For a virtual device, the QNX `qvmconf` and Android guest DTB must agree exactly.

Example concept:

```text
QNX qvmconf:
  VirtIO network MMIO = 0x1c0c0000
  VirtIO network IRQ  = 40

Android guest DTB:
  reg        = 0x1c0c0000
  interrupts = 40
```

If the address or interrupt is different, the Android driver may not probe or may hang.

The same rule applies to:

- VirtIO block
- VirtIO network
- virtual console
- virtual USB controller
- other emulated or paravirtualized devices

For a passed-through physical device, the guest DTB includes only the physical resources that QNX assigns to the guest.

---

## 9. Device Integration Contract

Every feature must be designed as a contract between four layers:

```text
QNX host backend
        |
        v
QNX qvm virtual-device or pass-through configuration
        |
        v
Android kernel driver and guest DTB
        |
        v
Android userspace, HAL, framework, and applications
```

### 9.1 VirtIO Device

```text
QNX backend
  +
VirtIO transport
  +
generic Android/Linux VirtIO frontend
```

### 9.2 Passed-Through Device

```text
QNX MMIO/IRQ/DMA/SMMU assignment
  +
real NXP Android kernel driver
  +
real NXP Android userspace/HAL stack
```

---

## 10. Network VirtIO Flow

VirtIO network is one of the simplest Android integrations.

```text
Physical Ethernet or host network
             |
             v
QNX network stack
             |
             v
QNX VirtIO network backend
             |
             v
Virtqueue
             |
             v
Android virtio_net driver
             |
             v
eth0
             |
             v
Android Ethernet and NetworkStack services
             |
             v
ConnectivityService
             |
             v
Android applications
```

Required Android-side elements normally include:

```text
Kernel:
  CONFIG_VIRTIO
  CONFIG_VIRTIO_MMIO or CONFIG_VIRTIO_PCI
  CONFIG_VIRTIO_NET

Board:
  matching DTB address and IRQ

Userspace:
  correct interface name
  init configuration
  DHCP or static configuration
  Android Ethernet configuration
  SELinux permissions
```

Unlike graphics, VirtIO network normally does not require a custom vendor Network HAL.

Success criteria:

```bash
ip link
ip address
ping <QNX-host-IP>
ping <LAN-device-IP>
ping <internet-IP>
```

Then enable ADB over TCP for development.

---

## 11. USB Virtualization Flow

USB support must be separated into two levels.

### 11.1 USB Controller Detection

QNX exposes a virtual USB host controller, normally an emulated xHCI controller.

```text
QNX USB stack
       |
       v
QNX virtual xHCI backend
       |
       v
Android xHCI kernel driver
       |
       v
Android USB core
```

### 11.2 USB Device Class

Each USB class has a different Android path.

#### USB Mouse or Keyboard

```text
xHCI
 -> USB HID
 -> Linux input subsystem
 -> /dev/input/eventX
 -> Android InputReader
 -> Android UI
```

Additional integration may include:

- `ueventd` permissions
- SELinux rules
- Android `.idc` input configuration

#### USB Mass Storage

```text
xHCI
 -> USB mass-storage driver
 -> SCSI block device
 -> /dev/block/sdX
 -> vold
 -> StorageManager
```

Additional integration may include:

- filesystem drivers
- vold configuration
- removable-storage configuration
- SELinux storage policy

#### USB Audio

```text
xHCI
 -> USB audio kernel driver
 -> ALSA
 -> Android Audio HAL
 -> AudioPolicy
 -> AudioFlinger
```

USB audio requires significantly more Android userspace and HAL integration than a USB mouse.

Therefore, the statement “USB works” must always identify the tested class:

```text
USB controller
USB HID
USB storage
USB audio
USB network adapter
USB Bluetooth adapter
```

---

## 12. GPU Pass-Through Flow

GPU pass-through cannot be completed by enabling a kernel driver only.

The complete path is:

```text
Physical NXP GPU
       |
       v
QNX pass-through configuration
  - MMIO
  - interrupts
  - DMA
  - SMMU/IOMMU
  - reserved memory
       |
       v
NXP GPU kernel driver inside Android
       |
       v
GPU device nodes
       |
       v
NXP firmware and userspace libraries
       |
       v
EGL / OpenGL ES / Vulkan
       |
       v
Gralloc allocator HAL
       |
       v
Graphics Composer HAL
       |
       v
SurfaceFlinger
       |
       v
Android UI
```

The default Trout graphics path is generally based on virtual graphics components such as:

```text
virtio-gpu
minigbm
Mesa
ranchu Graphics Composer
```

Those components are not the final solution when the physical NXP GPU is passed through.

They must be replaced by the matching NXP Android graphics stack.

### 12.1 GPU Is Not the Display Controller

The GPU renders buffers. The display controller scans buffers out to HDMI, LVDS, or another display interface.

```text
GPU
  -> renders framebuffer

Display controller
  -> sends framebuffer to the physical screen
```

Passing through only the GPU may allow rendering without producing a visible display.

The final architecture must choose one of these models:

```text
1. GPU pass-through + display-controller pass-through

2. GPU pass-through + shared display mechanism

3. VirtIO/shared GPU + QNX owns physical GPU and display
```

GPU/display ownership, SMMU support, NXP vendor driver availability, and QNX BSP support must be confirmed before implementation.

---

## 13. Android HAL, VINTF, init, and SELinux

### 13.1 HAL

The HAL connects Android Framework services to hardware or host services.

Examples:

```text
Vehicle HAL
Audio HAL
Graphics Composer HAL
Allocator/Gralloc HAL
Bluetooth HAL
Sensors HAL
```

A driver working in the kernel does not prove that its Android HAL is installed or working.

### 13.2 VINTF

VINTF is the contract between the Android framework and vendor implementation.

```text
Device manifest
       <- must match ->
Framework compatibility matrix
```

Typical failures include:

```text
HAL service not declared
Required HAL instance missing
Unsupported HAL version
Device manifest incompatible
```

### 13.3 init

Android `init` starts services and responds to properties and device events.

A HAL binary can exist in the filesystem but never run if the corresponding `init.rc` service is missing or incorrect.

### 13.4 ueventd

`ueventd` configures ownership and permissions for device nodes such as:

```text
/dev/dri/*
/dev/input/*
/dev/vsock
/dev/block/*
```

### 13.5 SELinux

Android SELinux can deny access even when Unix file permissions are correct.

Every new HAL, device node, property, socket, and host-guest service must have the correct SELinux types and allow rules.

---

## 14. Android Boot Flow on QNX

There are two practical boot methods.

---

## 15. Option A: Guest U-Boot and Composite Android Disk

This method is closest to the NXP Xen Trout reference.

```text
QNX host
  |
  v
qvm
  |
  +-- loads guest u-boot.bin
  |
  +-- exposes android-disk.img through VirtIO block
  |
  v
Guest U-Boot
  |
  v
Reads GPT and Android boot partitions
  |
  v
Loads Android kernel, ramdisks, DTB, and boot configuration
  |
  v
Android kernel
  |
  v
First-stage init
  |
  v
Mounts logical partitions from super
  |
  v
Android Automotive
```

Recommended deployment package:

```text
deploy/
|
+-- u-boot.bin
+-- android-disk.img
+-- qnx-trout-guest.dtb
+-- android.qvmconf
```

The U-Boot binary must be compatible with the QNX virtual board. A Xen-specific guest U-Boot must not be assumed to work unchanged on QNX.

---

## 16. Option B: Direct Kernel Boot

This method is useful during early bring-up.

```text
QNX qvm
  |
  +-- loads Android kernel Image
  +-- loads Android ramdisk
  +-- supplies guest DTB
  +-- supplies kernel command line or bootconfig
  +-- exposes Android storage disk
  |
  v
Android kernel starts directly
```

Recommended deployment package:

```text
deploy/
|
+-- Image
+-- combined-android-ramdisk.img
+-- qnx-trout-guest.dtb
+-- android-data-disk.img
+-- android.qvmconf
```

This method may require extracting and combining content from:

```text
boot.img
init_boot.img
vendor_boot.img
```

Direct boot is useful for debugging because it removes Guest U-Boot from the failure path.

---

## 17. Recommended Boot Strategy

Use the method that best matches the Linux guest configuration that already works on the QNX platform.

- If the working Linux guest uses direct kernel loading, begin Android bring-up with direct kernel loading.
- If the platform already has a validated guest U-Boot flow, use Guest U-Boot and a composite disk.
- For a production-like NXP Trout design, Guest U-Boot plus a composite Android disk is the architecture closest to the NXP Xen reference.

---

## 18. Understanding Android Build Images

A normal Android build produces many `.img` files because Android uses multiple partitions.

Example output:

```text
boot.img
dtb.img
init_boot.img
odm_dlkm.img
odm.img
product.img
ramdisk.img
super_empty.img
super.img
system_dlkm.img
system_ext.img
system.img
system_other.img
userdata.img
vbmeta.img
vbmeta_system.img
vbmeta_system_dlkm.img
vbmeta_vendor_dlkm.img
vendor_boot.img
vendor_dlkm.img
vendor.img
```

These files are not all deployed individually to QNX.

### 18.1 Main Boot Images

| Image | Purpose |
|---|---|
| `boot.img` | Android kernel and boot metadata, depending on Android version |
| `init_boot.img` | Generic Android ramdisk and first-stage init content |
| `vendor_boot.img` | Device/vendor ramdisk, fstab, modules, and vendor boot data |
| `dtb.img` | Guest hardware description when packaged separately |
| `vendor-bootconfig.img` | Vendor boot configuration data in builds that generate it |

### 18.2 Main Storage Images

| Image | Purpose |
|---|---|
| `super.img` | Container for dynamic logical partitions |
| `userdata.img` | Writable Android `/data` partition |
| `metadata.img` | Android metadata partition when used |
| `misc.img` | Boot-control and miscellaneous metadata when used |

Typical logical partitions inside `super.img` include:

```text
system
system_ext
product
vendor
odm
system_dlkm
vendor_dlkm
odm_dlkm
```

### 18.3 Verification Images

```text
vbmeta.img
vbmeta_system.img
vbmeta_system_dlkm.img
vbmeta_vendor_dlkm.img
```

These belong to Android Verified Boot.

The AVB design must be decided together with the final QNX/NXP secure-boot architecture. They should not be removed from a production design without a deliberate security decision.

### 18.4 Intermediate or Alternative Images

Examples:

```text
system.img
product.img
vendor.img
system_ext.img
odm.img
super_empty.img
ramdisk.img
vendor_ramdisk.img
vendor_boot-debug.img
vendor_boot-test-harness.img
```

Some of these are inputs to other images, unpacked partition images, debug variants, or test variants.

They are not normally copied one by one into the QNX deployment folder.

---

## 19. The Final Android Guest Disk

Instead of manually handling every Android partition image, the build should produce a deployable composite disk:

```text
android-disk.img
|
+-- GPT partition table
+-- boot
+-- init_boot
+-- vendor_boot
+-- vbmeta
+-- vbmeta_system
+-- super
+-- metadata
+-- misc
+-- userdata
```

The exact partition list depends on:

- Android release
- A/B or non-A/B update model
- AVB configuration
- Dynamic partition configuration
- `BoardConfig.mk`
- `fstab.qnx_trout`
- bootloader expectations

The packaging process must be generated from the product configuration. It must not be based on guessed offsets.

### Important

Do not write only `super.img` to the full SD card:

```bash
# Wrong:
sudo dd if=super.img of=/dev/sdX
```

`super.img` does not contain the QNX host, the complete Android boot chain, userdata, or the full guest disk partition table.

---

## 20. How to Inspect the Build Output

After lunch and build:

```bash
echo "$ANDROID_PRODUCT_OUT"
```

List all images:

```bash
find "$ANDROID_PRODUCT_OUT" \
    -maxdepth 1 \
    -type f \
    -name "*.img" \
    -printf "%f\n" | sort
```

Check file sizes:

```bash
ls -lh "$ANDROID_PRODUCT_OUT"/*.img
```

Identify image formats:

```bash
file "$ANDROID_PRODUCT_OUT"/*.img
```

### 20.1 Inspect a Composite Disk

```bash
fdisk -l "$ANDROID_PRODUCT_OUT/android-disk.img"
```

Or:

```bash
parted -s "$ANDROID_PRODUCT_OUT/android-disk.img" print
```

Or:

```bash
sgdisk -p "$ANDROID_PRODUCT_OUT/android-disk.img"
```

A valid composite disk should show a partition table and multiple Android partitions.

### 20.2 Inspect `super.img`

Use AOSP logical-partition tools:

```bash
lpdump "$ANDROID_PRODUCT_OUT/super.img"
```

Unpack its logical partitions:

```bash
mkdir -p /tmp/super_parts

lpunpack \
    "$ANDROID_PRODUCT_OUT/super.img" \
    /tmp/super_parts
```

Then inspect:

```bash
ls -lh /tmp/super_parts
```

### 20.3 Inspect Android Boot Images

```bash
mkdir -p /tmp/boot_unpack

unpack_bootimg \
    --boot_img "$ANDROID_PRODUCT_OUT/boot.img" \
    --out /tmp/boot_unpack
```

Repeat for the relevant boot image when supported:

```bash
mkdir -p /tmp/init_boot_unpack

unpack_bootimg \
    --boot_img "$ANDROID_PRODUCT_OUT/init_boot.img" \
    --out /tmp/init_boot_unpack
```

The AOSP host tools may be available under:

```text
out/host/linux-x86/bin/
```

Add them to `PATH` when required:

```bash
export PATH="$ANDROID_HOST_OUT/bin:$PATH"
```

### 20.4 Inspect Build Metadata

```bash
cat "$ANDROID_PRODUCT_OUT/android-info.txt"
```

```bash
less "$ANDROID_PRODUCT_OUT/misc_info.txt"
```

These files help identify partition sizes, dynamic partition groups, AVB configuration, and build assumptions.

---

## 21. How the NXP Xen Trout Reference Packages Android

The NXP Android Automotive User's Guide demonstrates Android Trout as a Xen guest on an i.MX 95 platform.

Its architecture is:

```text
Xen Dom0:
  Yocto Linux

Xen DomU:
  Android Trout
```

The NXP deployment package contains two main files:

```text
u-boot.bin
disk.img
```

NXP describes `disk.img` as a composite disk image containing the kernel and Android filesystem.

The files are copied into the Yocto host, and the Xen guest configuration:

- loads `u-boot.bin`
- exposes `disk.img` as a VirtIO disk
- starts Android Trout as DomU

The Android build command shown by NXP uses:

```bash
source build/envsetup.sh
lunch aosp_trout_arm64-bp2a-userdebug
m -j16
```

The generated `u-boot.bin` and `disk.img` are then used to launch Trout in Xen DomU.

This project copies the **architecture concept**, not the Xen-specific binaries or patches.

```text
NXP reference:
  Yocto + Xen + Xen guest U-Boot + disk.img

HyperNova target:
  QNX + qvm + QNX-compatible guest boot + android-disk.img
```

---

## 22. Deploying the Android Guest to QNX

### 22.1 What Is Flashed to the SD Card or eMMC?

The NXP boot media must first contain the QNX host platform:

```text
NXP boot firmware
QNX boot components
QNX IFS
QNX host filesystem
QNX Hypervisor components
```

Use the existing validated NXP/QNX BSP flashing procedure.

The Android guest does **not** replace the QNX host image.

### 22.2 What Is Copied to QNX?

Create a deployment directory on a filesystem accessible to QNX:

```text
/vm/android/
|
+-- android.qvmconf
+-- qnx-trout-guest.dtb
+-- u-boot.bin
+-- android-disk.img
```

For direct kernel boot:

```text
/vm/android/
|
+-- android.qvmconf
+-- qnx-trout-guest.dtb
+-- Image
+-- combined-android-ramdisk.img
+-- android-data-disk.img
```

Copy over the network when available:

```bash
scp android-disk.img root@<QNX_IP>:/vm/android/
scp u-boot.bin root@<QNX_IP>:/vm/android/
scp qnx-trout-guest.dtb root@<QNX_IP>:/vm/android/
scp android.qvmconf root@<QNX_IP>:/vm/android/
```

The files may also be copied through USB storage or included in the QNX data filesystem during image preparation.

---

## 23. File-Backed Disk vs Raw Partition

### 23.1 File-Backed Disk

```text
/vm/android/android-disk.img
```

Advantages during development:

- easy to copy
- easy to replace
- easy to back up
- easy to compare
- easy to restore
- does not require repartitioning the full boot device for every build

QNX maps the file or its host block backend through `virtio-blk`.

Inside Android, it normally appears as:

```text
/dev/vda
```

### 23.2 Dedicated Raw Partition

A complete SD/eMMC partition may be reserved for the Android guest.

Advantages:

- closer to a production block-device arrangement
- may avoid some host-filesystem overhead

Disadvantages:

- more dangerous during updates
- harder to copy and restore
- requires careful partition management
- accidental `dd` commands can destroy QNX data

Use file-backed storage for the initial bring-up unless the QNX BSP requires a raw block device.

---

## 24. QNX `qvmconf` Responsibilities

The QNX VM configuration describes the virtual board from the host side.

It controls:

- guest RAM
- vCPU count
- guest boot image
- guest DTB or FDT
- serial console
- VirtIO block
- VirtIO network
- virtual USB
- GPU/display pass-through
- MMIO mapping
- interrupts
- shared memory
- device ownership

Conceptual example only:

```text
system android-trout

ram <guest-memory>

cpu
cpu
cpu
cpu

load /vm/android/u-boot.bin

vdev virtio-blk
    hostdev <QNX block backend>
    loc <guest-MMIO-address>
    intr gic:<guest-IRQ>

vdev virtio-net
    loc <guest-MMIO-address>
    intr gic:<guest-IRQ>
    mac <guest-MAC>
    peer <QNX-network-peer>
```

The exact syntax, plugin names, addresses, IRQs, and host block path must come from:

- the installed QNX Hypervisor version
- the NXP QNX BSP
- the working Linux guest configuration
- the QNX virtual-device documentation

Do not copy a conceptual example as a final configuration.

---

## 25. Build and Deployment Workflow

### Step 1: Create the ARM64 Product

```bash
source build/envsetup.sh

lunch hypernova_qnx_trout_arm64-<release>-userdebug
```

The exact release suffix depends on the AOSP branch.

### Step 2: Build the QNX-Compatible Android Kernel

The kernel must contain:

- ARM64 support
- QNX guest virtual-platform support
- VirtIO block
- VirtIO network
- VirtIO transport used by QNX
- xHCI and required USB classes
- vsock when required
- NXP GPU/display drivers for pass-through
- required GKI symbols and vendor modules

### Step 3: Build the Guest DTB

Start from the already working QNX Linux guest virtual platform.

Add or adjust:

- Android boot requirements
- VirtIO devices
- reserved memory
- passed-through GPU/display nodes
- Android-specific devices

### Step 4: Integrate Android Userspace

Add or update:

```text
fstab
init.rc
ueventd.rc
VINTF
SELinux
Android properties
HAL packages
NXP vendor graphics stack
```

### Step 5: Build Android

```bash
m -j<N>
```

### Step 6: Generate the Guest Deployment Package

Preferred output:

```text
u-boot.bin
android-disk.img
qnx-trout-guest.dtb
android.qvmconf
```

If the product build creates only individual partition images, add a packaging step that creates the composite GPT disk.

### Step 7: Validate the Disk

```bash
file android-disk.img
fdisk -l android-disk.img
```

Confirm that all expected partitions exist.

### Step 8: Copy to QNX

Copy the guest package to `/vm/android/` or the chosen QNX path.

### Step 9: Start the VM

Start the Android `qvm` instance using the validated QNX configuration.

### Step 10: Debug Through Serial First

Initial success criteria:

```text
Android kernel starts
serial console works
first-stage init starts
VirtIO block appears
super logical partitions mount
Android init starts
adbd starts
```

Do not begin with GPU pass-through as the first proof of boot.

---

## 26. Recommended Implementation Milestones

### Milestone 1: Minimal Android Kernel Boot

Devices:

```text
vCPU
RAM
GIC
timer
UART
```

Success:

```text
Android kernel reaches serial output
```

### Milestone 2: VirtIO Block

Success:

```text
/dev/vda appears
partition table is readable
```

### Milestone 3: Android First-Stage init

Success:

```text
fstab is loaded
super is detected
logical partitions are created
```

### Milestone 4: Android Userspace

Success:

```text
Android init starts
servicemanager starts
logcat works
adbd works
```

### Milestone 5: VirtIO Network

Success:

```text
eth0 appears
Android receives an IP address
guest reaches QNX host and LAN
ADB over TCP works
```

### Milestone 6: USB HID

Success:

```text
virtual xHCI appears
USB mouse is detected
Android InputReader receives events
```

### Milestone 7: Temporary Graphics

Use software rendering or a simpler virtual graphics path first.

Success:

```text
SurfaceFlinger starts
SystemUI starts
CarService starts
HyperNova Launcher appears
```

### Milestone 8: GPU and Display Pass-Through

Success:

```text
NXP GPU driver probes
no SMMU faults
GPU firmware loads
EGL initializes
Gralloc initializes
Graphics Composer initializes
hardware-accelerated UI appears
```

### Milestone 9: Automotive Integration

Add:

```text
Vehicle HAL
audio
Bluetooth
GNSS
power management
Car Watchdog
suspend/resume
```

---

## 27. Main Technical Risks

### 27.1 Inheriting Too Much Cuttlefish Configuration

Trout ARM64 currently depends on Cuttlefish product and board components.

Every inherited package, property, kernel module, and HAL must be reviewed.

### 27.2 Using the Physical NXP DTB as the Guest DTB

The guest must only see devices assigned by QNX.

Passing the full physical-board DTB may expose unavailable devices and cause driver hangs, resource conflicts, or DMA/security problems.

### 27.3 Treating Android Like Ordinary Linux

A working kernel driver is only the first stage.

Android may still require:

```text
HAL
VINTF
init
ueventd
SELinux
framework configuration
```

### 27.4 GPU Vendor Stack Mismatch

The NXP kernel driver, firmware, EGL, Gralloc, HWC, and Android release must match.

Mixing vendor components from different BSP releases may cause initialization failures or crashes.

### 27.5 GPU Without Display Ownership

Passing only the GPU may not produce physical video output.

### 27.6 Incorrect Composite Disk Layout

The bootloader, fstab, AVB metadata, dynamic partitions, and QNX block mapping must agree on partition names and offsets.

### 27.7 Using the Current x86_64 Output

The existing product:

```text
hypernova_cockpit_x86_64
```

is suitable for workstation/Cuttlefish development only.

The NXP guest must be ARM64:

```text
hypernova_qnx_trout_arm64
```

x86_64 boot images cannot run on the NXP ARM64 target.

---

## 28. Information That Must Be Confirmed

Before finalizing GPU, USB, and disk configuration, confirm:

```text
Exact NXP board
Exact NXP SoC
QNX SDP version
QNX Hypervisor version
NXP QNX BSP version
QNX Advanced Virtualization Framework availability
SMMU Manager availability
virtual USB plugin availability
GPU pass-through support for this BSP
display-controller ownership model
NXP Android BSP release
Android/AOSP release
guest boot method
QNX host block backend path
```

The NXP guide used as a reference demonstrates Trout on Xen on an i.MX 95 platform. It proves the architectural approach, but it does not prove that every feature is supported identically on a different NXP SoC or QNX BSP.

---

## 29. Definition of the Final Deliverable

The final result is not simply `aosp_trout_arm64`.

It is:

```text
HyperNova Android Automotive Guest
for an NXP ARM64 platform
running on QNX Hypervisor
based on the Android Trout reference
```

The source-side deliverable includes:

```text
device/hypernova/qnx_trout_arm64/
NXP vendor integration
QNX-compatible kernel configuration
QNX Android guest DTB
SELinux/VINTF/init/fstab configuration
guest disk packaging tool
```

The runtime deployment deliverable includes either:

```text
u-boot.bin
android-disk.img
qnx-trout-guest.dtb
android.qvmconf
```

or:

```text
Image
combined-android-ramdisk.img
android-data-disk.img
qnx-trout-guest.dtb
android.qvmconf
```

---

## 30. Questions and Answers

### Q1: Is using Android Trout as the base product the correct decision?

Yes. Trout is designed as a virtualized Android Automotive reference and can be extended to create a new ARM64 IVI target.

### Q2: Is unchanged Trout the final guest?

No. A new HyperNova product must inherit Trout and replace Cuttlefish-specific board, boot, graphics, storage, and host assumptions.

### Q3: Is changing the DTB enough?

No. The project also needs a matching kernel, `BoardConfig.mk`, `fstab`, init files, VINTF, SELinux, and vendor HAL stack.

### Q4: Should the complete NXP physical-board DTB be used?

No. Create a guest DTB describing the QNX virtual board, then add only the passed-through physical devices.

### Q5: Can the existing x86_64 images be copied to NXP?

No. The NXP target requires ARM64 images.

### Q6: Which Android image should be copied to QNX?

Prefer a composite guest disk such as:

```text
android-disk.img
```

It contains the Android partitions required by the guest boot flow.

### Q7: Should `super.img` be written directly to the SD card?

No. `super.img` is only the dynamic-partition container. It is not the QNX host image and not a complete Android guest disk.

### Q8: What is flashed to the NXP SD card or eMMC?

The QNX host BSP image is flashed using the validated NXP/QNX procedure.

### Q9: Where is Android stored?

During development, Android can be stored as a file such as:

```text
/vm/android/android-disk.img
```

QNX exposes it to Android through VirtIO block.

### Q10: What does Android see?

The guest typically sees the QNX block backend as:

```text
/dev/vda
```

### Q11: Does VirtIO network need a custom HAL?

Normally no. It needs the kernel driver, DTB/qvm matching, interface configuration, Android Ethernet integration, and SELinux.

### Q12: Does virtual USB need a custom Android HAL?

It depends on the USB class. HID can use the standard input stack. USB audio requires the Android audio stack and Audio HAL integration.

### Q13: Is a GPU kernel driver enough for GPU pass-through?

No. Android also needs the matching NXP firmware, userspace GPU libraries, Gralloc, Graphics Composer, VINTF, init, and SELinux.

### Q14: Is GPU pass-through the first milestone?

No. Boot Android through serial and VirtIO block first. Add networking and basic userspace before debugging GPU pass-through.

### Q15: Is Cuttlefish used on the final NXP target?

No. Cuttlefish is only a workstation testing platform. QNX `qvm` is the virtual-machine manager on the final target.

---

## 31. Reference Documentation

1. **Android Open Source Project — Automotive Virtualization Reference Platform**  
   https://source.android.com/docs/automotive/virtualization/reference_platform

2. **NXP — Android Automotive User's Guide, UG10176**  
   Chapter 10 demonstrates Android Trout as a Xen guest, including `u-boot.bin`, `disk.img`, VirtIO configuration, build steps, and deployment.  
   https://www.nxp.com/docs/en/user-guide/UG10176.pdf

3. **QNX Hypervisor — Building Linux and Android Guests**  
   https://www.qnx.com/developers/docs/8.0/com.qnx.doc.hypervisor.user/topic/build/linux.html

4. **QNX Hypervisor — Virtual I/O (VirtIO)**  
   https://www.qnx.com/developers/docs/8.0/com.qnx.doc.hypervisor.user/topic/perform/virtio.html

---

## 32. Final Architecture Summary

```text
Build workstation
|
+-- AOSP Trout ARM64 reference
+-- HyperNova QNX product
+-- QNX-compatible Android kernel
+-- QNX guest DTB
+-- NXP vendor graphics/HAL stack
|
v
Android build
|
+-- individual Android partition images
|
v
Guest packaging stage
|
+-- u-boot.bin
+-- android-disk.img
+-- qnx-trout-guest.dtb
+-- android.qvmconf
|
v
NXP storage
|
+-- QNX host image
+-- /vm/android/ guest package
|
v
QNX qvm
|
+-- VirtIO block
+-- VirtIO network
+-- virtual USB
+-- GPU/display pass-through
|
v
HyperNova Android Automotive Guest
```
