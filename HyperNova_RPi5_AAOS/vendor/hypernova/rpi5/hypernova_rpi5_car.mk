#
# HyperNova Raspberry Pi 5 Android Automotive product.
#
# The stock Raspberry Pi 5 AAOS device configuration provides:
# - Kernel
# - Boot configuration
# - Graphics and DRM
# - Audio
# - Ethernet
# - Bluetooth
# - Android Automotive framework
#
# HyperNova replaces only the visible application layer.
#

$(call inherit-product, device/brcm/rpi5/aosp_rpi5_car.mk)

$(call inherit-product, vendor/hypernova/rpi5/hypernova_rpi5_content.mk)

# ============================================================
# PRODUCT IDENTITY
# ============================================================

PRODUCT_NAME := hypernova_rpi5_car
PRODUCT_DEVICE := rpi5
PRODUCT_BRAND := HyperNova
PRODUCT_MODEL := HyperNova RPi5 Automotive
PRODUCT_MANUFACTURER := HyperNova

# Keep the product classified as Android Automotive.
PRODUCT_CHARACTERISTICS := automotive

# ============================================================
# HYPERNOVA FINAL SETTINGS / IVI POLISH
# ============================================================

# Product-time resource overlays. This keeps CarLatinIME system-owned while
# adapting its geometry and dark palette to the 1080x1920 cockpit display.
PRODUCT_PACKAGE_OVERLAYS += \
    vendor/hypernova/rpi5/resource_overlay

# The audited RPi build receives network UTC time correctly but reports that
# automatic time-zone detection is unsupported. Seed the fresh image with the
# actual IANA Egypt zone. The Settings app still lets the driver change it.
PRODUCT_SYSTEM_PROPERTIES += \
    persist.sys.timezone=Africa/Cairo

# The physical display is connected on HDMI port 0 and Linux enumerates
# vc4-hdmi-0 as ALSA card 0. Force the RPi primary audio HAL to that card
# instead of leaving the card selection at its -1 auto-discovery default.
PRODUCT_VENDOR_PROPERTIES += \
    persist.vendor.audio.pcm.card=0

# ============================================================
# HyperNova CarSystemUI chrome
# Keep CarSystemUI infrastructure installed while removing
# persistent AAOS top/bottom/left/right system bars.
# ============================================================
PRODUCT_PACKAGES += \
    HyperNovaCarSystemUIOverlay

