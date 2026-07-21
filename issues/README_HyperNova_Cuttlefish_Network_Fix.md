# HyperNova Cuttlefish Internet and DNS Fix

## 1. Purpose

This README documents the complete networking problem that prevented the HyperNova Navigation app from loading the real MapLibre/OpenFreeMap map inside Android Automotive Cuttlefish.

It covers the root cause, failed approaches, permanent Android and Ubuntu fixes, build/deployment, reboot behavior, and recovery steps.

---

## 2. Environment

### Host

```text
OS: Ubuntu 24.04.4 LTS
Architecture: x86_64
AOSP root: /mnt/wwn-0x5002538e7006e10b-part3
Typical uplink: wlp111s0
```

The host tool detects the active IPv4 default-route interface automatically, so it is not permanently tied to one Wi-Fi or Ethernet device.

### Android build

```text
Platform: VanillaIceCream
Build ID: AP3A.240905.015.A2
Target product: hypernova_cockpit_x86_64
Variant: userdebug
Product output: out/target/product/trout_x86_64
```

### Cuttlefish network

```text
Host bridge: cvd-ebr
Bridge address: 192.168.98.1/24
Guest interface: eth1
Typical guest address: 192.168.98.117/24
Gateway: 192.168.98.1
Subnet: 192.168.98.0/24
```

### Navigation app

```text
Package: com.hypernova.navigation
Activity: com.hypernova.navigation/.MainActivity
Map engine: MapLibre Native Android
Map style: https://tiles.openfreemap.org/styles/liberty
```

---

## 3. Original Problem

The Navigation app opened and `libmaplibre.so` loaded, but the map stayed blank.

The UI remained on:

```text
Connecting to map service...
Loading real map
```

Logcat showed:

```text
Unable to resolve host "tiles.openfreemap.org":
No address associated with hostname
```

Android NetworkMonitor also failed to resolve:

```text
connectivitycheck.gstatic.com
www.google.com
play.googleapis.com
```

This proved the problem was not MapLibre rendering. It was an Android/Cuttlefish networking problem.

---

## 4. Root Cause

### Android side

The original automotive Cuttlefish overlay configured `eth1` as an internal vehicle network:

```xml
<item>eth1;11,14,15,27,28;</item>
```

Runtime capabilities included:

```text
NOT_METERED
TRUSTED
NOT_VPN
VEHICLE_INTERNAL
NOT_VCN_MANAGED
```

The Navigation app had the `INTERNET` permission, but Android ConnectivityService did not expose `eth1` as a normal unrestricted default internet network.

### Ubuntu host side

Cuttlefish also needed a valid path through the host:

```text
Cuttlefish guest
    ↓
cvd-ebr
    ↓
Ubuntu IPv4 forwarding
    ↓
NAT / MASQUERADE
    ↓
Current Wi-Fi or Ethernet uplink
    ↓
Internet
```

The guest also needed a reliable DNS path. The final setup makes `systemd-resolved` listen on:

```text
192.168.98.1:53
```

and redirects guest DNS traffic to this local resolver.

---

## 5. Failed Approaches

### Mixing Internet and Vehicle Internal on `eth1`

Tested:

```xml
<item>eth1;11,12,13,14,15,27,28;</item>
```

It added `INTERNET` and `NOT_RESTRICTED` but kept `VEHICLE_INTERNAL`. The interface still did not behave as a clean normal Android default network.

### Manual policy-routing rule

Tested:

```bash
adb shell ip rule add priority 31000 lookup 1015
```

It only changed Linux kernel routing temporarily. It disappeared after reboot or `netd` restart and was not a proper Android ConnectivityService solution.

Do not use this broad rule in the final system.

### Static Ethernet configuration

Tested:

```xml
<item>eth1;12,13,14,15;ip=192.168.98.117/24 gateway=192.168.98.1 dns=192.168.98.1</item>
```

Android reported static assignment, but runtime state became:

```text
networkAgent: null
ipClient: null
LinkAddresses: []
DnsAddresses: []
Routes: []
```

The final solution therefore uses DHCP.

### Direct public DNS only

The guest initially received `8.8.8.8` and `8.8.4.4`, but the development network did not provide a reliable direct DNS path. The final host setup transparently redirects DNS to `192.168.98.1:53`.

---

## 6. Final Architecture

```text
┌──────────────────────────────────────┐
│ HyperNova Navigation                 │
│ MapLibre + OpenFreeMap               │
└──────────────────┬───────────────────┘
                   │ HTTPS / DNS
                   ▼
┌──────────────────────────────────────┐
│ Android eth1                         │
│ DHCP normal internet network         │
│ Android default network              │
└──────────────────┬───────────────────┘
                   │ 192.168.98.0/24
                   ▼
┌──────────────────────────────────────┐
│ Ubuntu cvd-ebr                       │
│ 192.168.98.1                         │
│ DNS listener + IPv4 forwarding       │
└──────────────────┬───────────────────┘
                   │ NAT / MASQUERADE
                   ▼
┌──────────────────────────────────────┐
│ Current host uplink                  │
│ Wi-Fi or Ethernet                    │
└──────────────────┬───────────────────┘
                   ▼
                Internet
```

Interface responsibilities:

```text
eth1    → normal Android internet
macsec0 → internal vehicle network
```

---

## 7. HyperNova Connectivity Overlay

The permanent customization belongs to HyperNova, not the upstream Google directory.

```text
device/hypernova/cockpit/ConnectivityOverlay/
├── Android.bp
├── AndroidManifest.xml
└── res/
    └── values/
        └── config.xml
```

The HyperNova product makefile includes:

```makefile
PRODUCT_PACKAGES += \
    HyperNovaLauncher \
    HyperNovaConnectivityOverlay
```

### `Android.bp`

Path:

```text
device/hypernova/cockpit/ConnectivityOverlay/Android.bp
```

```bp
package {
    default_applicable_licenses: ["Android-Apache-2.0"],
}

runtime_resource_overlay {
    name: "HyperNovaConnectivityOverlay",
    resource_dirs: ["res"],
    manifest: "AndroidManifest.xml",
    sdk_version: "current",
    product_specific: true,
    overrides: ["ConnectivityOverlayCuttleFish"],
}
```

The `overrides` property replaces the default Cuttlefish connectivity overlay.

### `AndroidManifest.xml`

Path:

```text
device/hypernova/cockpit/ConnectivityOverlay/AndroidManifest.xml
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.hypernova.connectivity.resources.cuttlefish">

    <application android:hasCode="false" />

    <overlay
        android:targetPackage="com.android.connectivity.resources"
        android:targetName="ServiceConnectivityResourcesConfig"
        android:priority="1"
        android:isStatic="true" />

</manifest>
```

### `config.xml`

Path:

```text
device/hypernova/cockpit/ConnectivityOverlay/res/values/config.xml
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>

    <bool name="config_vehicleInternalNetworkAlwaysRequested">true</bool>

    <string-array
        translatable="false"
        name="config_ethernet_interfaces">

        <!--
            macsec0 remains the internal vehicle interface.

            Values used by this AOSP branch:
            11 = NOT_METERED
            14 = TRUSTED
            15 = NOT_VPN
            27 = VEHICLE_INTERNAL
            28 = NOT_VCN_MANAGED
        -->
        <item>macsec0;11,14,15,27,28</item>

        <!--
            eth1 is the normal Android internet interface.
            The third field is omitted, so DHCP is used.

            Values used by this AOSP branch:
            11 = NOT_METERED
            12 = INTERNET
            13 = NOT_RESTRICTED
            14 = TRUSTED
            15 = NOT_VPN
            28 = NOT_VCN_MANAGED
            37 = NOT_BANDWIDTH_CONSTRAINED
        -->
        <item>eth1;11,12,13,14,15,28,37</item>

    </string-array>

    <string
        translatable="false"
        name="config_ethernet_iface_regex">(eth|macsec)\\d+</string>

</resources>
```

Important:

```text
Do not add a static-IP third field.
Do not add VEHICLE_INTERNAL to eth1.
Keep macsec0 as the internal vehicle interface.
Recheck numeric capability values after an Android branch upgrade.
```

---

## 8. Host Network Tool

Project path:

```text
device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh
```

Supported commands:

```bash
sudo ./hypernova-cuttlefish-network.sh apply
sudo ./hypernova-cuttlefish-network.sh verify
sudo ./hypernova-cuttlefish-network.sh cleanup
```

The tool:

- Detects the current IPv4 default-route uplink.
- Enables IPv4 forwarding.
- Configures `systemd-resolved` to listen on `192.168.98.1:53`.
- Creates dedicated HyperNova firewall chains.
- Redirects guest UDP/TCP DNS traffic to the host resolver.
- Forwards traffic from `cvd-ebr` to the active uplink.
- Allows established replies back to Cuttlefish.
- Applies NAT/MASQUERADE to `192.168.98.0/24`.
- Removes recognized stale HyperNova/Cuttlefish rules.
- Does not flush unrelated firewall rules.
- Saves pre-change firewall audits.
- Can be safely reapplied.

Managed chains:

```text
HN_CVD_INPUT
HN_CVD_FORWARD
HN_CVD_DNS
HN_CVD_NAT
```

Host configuration files:

```text
/etc/systemd/resolved.conf.d/90-hypernova-cuttlefish.conf
/etc/sysctl.d/90-hypernova-cuttlefish.conf
```

Resolver configuration:

```ini
[Resolve]
DNSStubListenerExtra=192.168.98.1
```

Forwarding configuration:

```ini
net.ipv4.ip_forward=1
```

---

## 9. Systemd Service

Project source:

```text
device/hypernova/cockpit/tools/hypernova-cuttlefish-network.service
```

Installed path:

```text
/etc/systemd/system/hypernova-cuttlefish-network.service
```

```ini
[Unit]
Description=HyperNova Cuttlefish Ethernet forwarding and DNS
Wants=network-online.target
After=network-online.target systemd-resolved.service

[Service]
Type=oneshot
ExecStart=/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh apply
ExecStop=/mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh cleanup
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
```

For this oneshot service, the normal successful state is:

```text
active (exited)
```

### Repository-path warning

The unit contains an absolute AOSP path. If the repository is moved, update `ExecStart` and `ExecStop`, then run:

```bash
sudo systemctl daemon-reload
sudo systemctl restart hypernova-cuttlefish-network.service
```

---

## 10. Install or Reinstall the Host Service

```bash
cd /mnt/wwn-0x5002538e7006e10b-part3
```

Make the tool executable:

```bash
chmod +x \
    device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh
```

Apply it:

```bash
sudo \
    device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh \
    apply
```

Install the unit:

```bash
sudo install -m 0644 \
    device/hypernova/cockpit/tools/hypernova-cuttlefish-network.service \
    /etc/systemd/system/hypernova-cuttlefish-network.service
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable hypernova-cuttlefish-network.service
sudo systemctl restart hypernova-cuttlefish-network.service
```

Verify:

```bash
systemctl is-enabled hypernova-cuttlefish-network.service
systemctl is-active hypernova-cuttlefish-network.service
sudo systemctl status hypernova-cuttlefish-network.service
```

Expected:

```text
enabled
active
```

---

## 11. Build the Overlay

Use the already configured AOSP environment when available:

```bash
cd /mnt/wwn-0x5002538e7006e10b-part3
m HyperNovaConnectivityOverlay -j4
```

Output:

```text
out/target/product/trout_x86_64/product/overlay/HyperNovaConnectivityOverlay.apk
```

### Lunch syntax warning

This Android branch uses release-aware combinations:

```text
<product>-<release>-<variant>
```

Do not blindly use:

```bash
lunch hypernova_cockpit_x86_64-userdebug
```

When a new build environment is required, inspect valid combinations instead of guessing.

---

## 12. Deploy to Running Cuttlefish

```bash
adb root
adb wait-for-device
adb remount
```

Remove the old development overlay:

```bash
adb shell rm -f \
    /product/overlay/ConnectivityOverlayCuttleFish.apk
```

Push the HyperNova overlay:

```bash
adb push \
    out/target/product/trout_x86_64/product/overlay/HyperNovaConnectivityOverlay.apk \
    /product/overlay/HyperNovaConnectivityOverlay.apk
```

Reboot:

```bash
adb shell sync
adb reboot
adb wait-for-device
```

Wait for full Android boot:

```bash
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
    sleep 2
done

sleep 10
```

---

## 13. Verify the Installed Overlay

```bash
adb shell pm path \
    com.hypernova.connectivity.resources.cuttlefish
```

Expected:

```text
/product/overlay/HyperNovaConnectivityOverlay.apk
```

List overlays:

```bash
adb shell cmd overlay list
```

Check whether the old Google overlay remains:

```bash
adb shell pm path \
    com.google.android.connectivity.resources.cuttlefish
```

The old automotive overlay should not be active in the final HyperNova setup.

---

## 14. Verify Ethernet Runtime

```bash
adb shell dumpsys ethernet
```

For `eth1`, verify:

```text
Interface is up.
networkAgent is non-null.
ipClient is non-null.
LinkAddresses contains an IPv4 address.
DnsAddresses is non-empty.
Routes contains a default route.
```

Expected capabilities:

```text
INTERNET
NOT_RESTRICTED
TRUSTED
NOT_VPN
NOT_VCN_MANAGED
NOT_BANDWIDTH_CONSTRAINED
```

`eth1` must not include:

```text
VEHICLE_INTERNAL
```

---

## 15. Verify the Android Default Network

```bash
adb shell dumpsys connectivity
```

Look for an active Ethernet network with:

```text
CONNECTED
INTERNET
NOT_RESTRICTED
VALIDATED
```

Typical values:

```text
Guest IP: 192.168.98.117/24
Gateway: 192.168.98.1
Transport: ETHERNET
```

Android/netd may create fwmark-based routing rules. These are normal.

Do not add the old broad manual rule:

```bash
adb shell ip rule add priority 31000 from all lookup 1015
```

---

## 16. Verify DNS and HTTPS

DNS tests:

```bash
adb shell ping -c 1 -W 1 tiles.openfreemap.org
adb shell ping -c 1 -W 1 demotiles.maplibre.org
adb shell ping -c 1 -W 1 connectivitycheck.gstatic.com
```

The important result is hostname-to-IP resolution.

Success:

```text
PING tiles.openfreemap.org (104.x.x.x)
```

Failure:

```text
ping: unknown host tiles.openfreemap.org
```

Public ICMP may be blocked, so zero echo replies do not automatically mean failure.

When Android contains `curl`:

```bash
adb shell curl -4 -I \
    --max-time 15 \
    https://tiles.openfreemap.org/styles/liberty
```

Expected:

```text
HTTP/1.1 200 OK
Content-Type: application/json
```

HTTPS is a better functional test than public ping.

---

## 17. Verify the Navigation App

```bash
adb logcat -c

adb shell am force-stop \
    --user 10 \
    com.hypernova.navigation

adb shell am start \
    --user 10 \
    -n com.hypernova.navigation/.MainActivity

sleep 15
```

Check errors:

```bash
adb logcat -d -v brief \
    | grep -iE \
    "Unable to resolve host|loading style failed"
```

Expected:

```text
No output
```

The real map should display Cairo streets, Arabic and English labels, the Nile, map controls, and the app's online state.

---

# 18. What Happens After Restarting the Laptop?

Normal startup:

```text
Ubuntu boots
    ↓
NetworkManager connects to Wi-Fi or Ethernet
    ↓
network-online.target is reached
    ↓
hypernova-cuttlefish-network.service runs
    ↓
Host DNS listener, forwarding and NAT are configured
    ↓
Cuttlefish starts
    ↓
eth1 receives DHCP configuration
    ↓
Android selects Ethernet as the default network
    ↓
MapLibre loads OpenFreeMap
```

Normally, no manual route or DNS command is required after restart.

---

# 19. Quick Check After Restart

```bash
systemctl is-enabled hypernova-cuttlefish-network.service
systemctl is-active hypernova-cuttlefish-network.service
sudo systemctl status hypernova-cuttlefish-network.service
```

Expected:

```text
enabled
active
```

Run the project verification tool:

```bash
sudo \
    /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh \
    verify
```

After starting Cuttlefish:

```bash
adb shell getprop sys.boot_completed
```

Expected:

```text
1
```

Check the Android network:

```bash
adb shell dumpsys connectivity \
    | grep -iE "ETHERNET|INTERNET|VALIDATED" \
    | head -30
```

Check DNS:

```bash
adb shell ping -c 1 -W 1 tiles.openfreemap.org
```

---

# 20. Troubleshooting After Restart

## Service is inactive or failed

```bash
sudo systemctl restart hypernova-cuttlefish-network.service
sudo systemctl status hypernova-cuttlefish-network.service
```

Read logs:

```bash
sudo journalctl \
    -u hypernova-cuttlefish-network.service \
    -b \
    --no-pager
```

## Ubuntu started without internet

Connect to Wi-Fi or Ethernet, then run:

```bash
sudo systemctl restart hypernova-cuttlefish-network.service
```

The tool detects the active uplink when it runs.

## You changed from Wi-Fi to Ethernet

```bash
sudo systemctl restart hypernova-cuttlefish-network.service
```

This recreates NAT rules for the current default-route interface.

## You enabled or disabled a VPN

Check the current route:

```bash
ip -4 route show default
```

Then restart:

```bash
sudo systemctl restart hypernova-cuttlefish-network.service
```

## DNS still fails

Check the host DNS listener:

```bash
sudo ss -luntp | grep "192.168.98.1:53"
```

Expected TCP and UDP listeners.

Restart the resolver and HyperNova service:

```bash
sudo systemctl restart systemd-resolved
sudo systemctl restart hypernova-cuttlefish-network.service
```

Retest:

```bash
adb shell ping -c 1 -W 1 tiles.openfreemap.org
```

## `cvd-ebr` is missing

```bash
ip -br addr | grep cvd-ebr
```

When it does not exist, Cuttlefish networking has not been created yet. Start Cuttlefish, then run:

```bash
sudo systemctl restart hypernova-cuttlefish-network.service
```

## Android Ethernet is not connected

```bash
adb shell dumpsys ethernet
```

If `networkAgent` or `ipClient` is null, verify the installed overlay:

```bash
adb shell pm path \
    com.hypernova.connectivity.resources.cuttlefish
```

Expected:

```text
/product/overlay/HyperNovaConnectivityOverlay.apk
```

Check for the old APK:

```bash
adb shell ls -l \
    /product/overlay/ConnectivityOverlayCuttleFish.apk
```

Redeploy the HyperNova overlay when necessary.

## Map remains on “Loading real map”

```bash
adb logcat -c

adb shell am force-stop \
    --user 10 \
    com.hypernova.navigation

adb shell am start \
    --user 10 \
    -n com.hypernova.navigation/.MainActivity

sleep 15

adb logcat -d \
    | grep -iE \
    "Mbgl|MapLibre|openfreemap|Unable to resolve|loading style failed" \
    | tail -100
```

Interpretation:

```text
Unable to resolve host
→ DNS or Android default-network problem.

Connection timed out
→ Forwarding, firewall, captive portal or uplink problem.

HTTP error
→ Remote provider, proxy or access-policy problem.

No network error but blank map
→ Map style or rendering problem.
```

## Repository path changed

Edit:

```bash
sudo nano \
    /etc/systemd/system/hypernova-cuttlefish-network.service
```

Correct `ExecStart` and `ExecStop`, then run:

```bash
sudo systemctl daemon-reload
sudo systemctl restart hypernova-cuttlefish-network.service
```

## Duplicate firewall rules are suspected

Do not flush all firewall rules.

Reapply only the HyperNova configuration:

```bash
sudo \
    /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh \
    apply
```

Verify managed chains:

```bash
sudo iptables -S HN_CVD_INPUT
sudo iptables -S HN_CVD_FORWARD
sudo iptables -t nat -S HN_CVD_DNS
sudo iptables -t nat -S HN_CVD_NAT
```

---

# 21. One-Command Recovery Sequence

Use this after connecting the laptop to the internet and starting Cuttlefish:

```bash
sudo systemctl restart systemd-resolved
sudo systemctl restart hypernova-cuttlefish-network.service

adb wait-for-device

until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
    sleep 2
done

sleep 10

adb shell ping -c 1 -W 1 \
    tiles.openfreemap.org

adb shell am force-stop \
    --user 10 \
    com.hypernova.navigation

adb shell am start \
    --user 10 \
    -n com.hypernova.navigation/.MainActivity
```

Then inspect:

```bash
adb logcat -d \
    | grep -iE \
    "Unable to resolve host|loading style failed"
```

---

# 22. Verification Checklist

```text
[ ] Host has IPv4 internet
[ ] hypernova-cuttlefish-network.service is enabled
[ ] hypernova-cuttlefish-network.service is active
[ ] cvd-ebr exists
[ ] cvd-ebr has 192.168.98.1/24
[ ] systemd-resolved listens on 192.168.98.1:53
[ ] net.ipv4.ip_forward = 1
[ ] HN_CVD_* firewall chains exist
[ ] NAT uses the current host uplink
[ ] HyperNovaConnectivityOverlay is installed
[ ] eth1 is up
[ ] eth1 uses DHCP
[ ] eth1 has an IPv4 address
[ ] eth1 has DNS addresses
[ ] eth1 has a default route
[ ] eth1 has INTERNET
[ ] eth1 has NOT_RESTRICTED
[ ] eth1 does not have VEHICLE_INTERNAL
[ ] Android Ethernet is CONNECTED
[ ] Android Ethernet is VALIDATED
[ ] tiles.openfreemap.org resolves
[ ] OpenFreeMap HTTPS succeeds
[ ] No "Unable to resolve host" error
[ ] No "loading style failed" error
[ ] Cairo map is visibly rendered
```

---

# 23. Known Limitations

- Public ICMP can be blocked while HTTPS still works.
- Captive-portal Wi-Fi may require browser login first.
- Corporate networks may block DNS, forwarding, VPN traffic, or map providers.
- Changing Wi-Fi/Ethernet/VPN after boot may require restarting the HyperNova service.
- Current defaults expect `cvd-ebr`, `192.168.98.0/24`, and `192.168.98.1`.
- Numeric Android network-capability values must be rechecked after an AOSP branch upgrade.

---

# 24. Rollback

Clean up HyperNova-managed host networking:

```bash
sudo \
    /mnt/wwn-0x5002538e7006e10b-part3/device/hypernova/cockpit/tools/hypernova-cuttlefish-network.sh \
    cleanup
```

Disable the service:

```bash
sudo systemctl disable --now \
    hypernova-cuttlefish-network.service
```

Remove the installed unit:

```bash
sudo rm -f \
    /etc/systemd/system/hypernova-cuttlefish-network.service

sudo systemctl daemon-reload
```

The cleanup intentionally leaves global IPv4 forwarding unchanged because other host services may depend on it.

---

# 25. Questions and Answers

## Why was the MapLibre screen blank?

MapLibre loaded, but Android could not resolve or reach the OpenFreeMap servers.

## Was the Navigation app code the problem?

No. Android NetworkMonitor showed the same DNS failures.

## Why is `eth1` no longer `VEHICLE_INTERNAL`?

It is now the normal Android internet interface. `macsec0` remains the dedicated internal vehicle interface.

## Why use DHCP?

DHCP correctly creates `IpClient`, `NetworkAgent`, IP address, DNS, gateway, and routes. The tested static overlay did not start the interface correctly.

## Why is the Ubuntu host tool required?

Cuttlefish is behind the Ubuntu host on a private subnet. The host must forward, translate, and resolve its traffic.

## Will it work after restarting the laptop?

Normally yes because the systemd service is enabled. When the laptop starts without internet or the default uplink changes later, run:

```bash
sudo systemctl restart hypernova-cuttlefish-network.service
```

## Will it work on every possible network?

It works on normal networks that allow DNS, HTTPS, and forwarded traffic. Captive portals, restrictive company networks, and some VPN configurations can require extra action.

## Should the old manual `ip rule 31000` command be used?

No. The final default network is managed by Android ConnectivityService and netd.

---

# 26. Final Verified Result

```text
Cuttlefish internet connectivity: WORKING
Android Ethernet default network: WORKING
DNS resolution: WORKING
OpenFreeMap HTTPS: WORKING
MapLibre Cairo map: WORKING
Persistence after reboot: VERIFIED
HyperNova-owned connectivity overlay: INTEGRATED
Host service: ENABLED AND ACTIVE
```
