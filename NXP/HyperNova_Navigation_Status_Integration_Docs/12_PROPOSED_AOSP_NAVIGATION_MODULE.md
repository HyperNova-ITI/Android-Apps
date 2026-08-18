# Proposed AOSP Navigation Module (Not Applied)

The current AOSP tree contains no existing HyperNova Navigation directory,
`Android.bp`, prebuilt APK, or `PRODUCT_PACKAGES` entry. The task authorized
replacement only in an existing module, so no Navigation AOSP files were
created.

If a new module is authorized, the smallest proposed integration is:

```text
device/hypernova/cockpit/apps/HyperNovaNavigation/
├── Android.bp
└── HyperNovaNavigation.apk
```

Conceptual `Android.bp`:

```bp
android_app_import {
    name: "HyperNovaNavigation",
    apk: "HyperNovaNavigation.apk",
    certificate: "platform",
    system_ext_specific: true,
    dex_preopt: {
        enabled: false,
    },
}
```

The product would also need an authorized
`PRODUCT_PACKAGES += HyperNovaNavigation` entry. Platform signing keeps the
existing signature-protected HyperNova command IPC relationship. Exact
privileged placement should be reviewed against the final Navigation manifest;
the proposal does not grant new permissions.

Only after those files exist should the build command become:

```bash
m HyperNovaNavigation HyperNovaLauncher -j10
```

This document is a proposal only. No AOSP build or product file was modified.
