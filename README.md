# Custom Attributes Plugin for VMware

> **Community Plugins — Not Officially Supported by HPE**
>
> These plugins are built by HPE as community contributions. They are **not** official HPE products and carry no HPE support, SLA or warranty. You are free to use them, fork them, and extend them to suit your environment. Pull requests and adaptations are welcome.
---

A Morpheus plugin that adds a **Custom Attributes** tab to the instance page and to the server page of VMs running on VMware vSphere. Users can view, add, edit and clear vCenter custom attributes without leaving Morpheus.

## Features
- Shows the VM's vCenter custom attributes that have a value (name and value) in a card.
- **Edit** opens a dialog that lists every attribute that applies to VMs, including those with no value. Change values in place, add new attributes with **ADD**, and commit everything with **SAVE**. Setting a value to empty clears it. The attribute definition stays in vCenter.
- A new attribute is created in vCenter automatically. Its **Type** is Virtual Machine (the default) or Global. A Global attribute applies to every vCenter object type.
- On the **instance page** of an instance with several VMs, a **Virtual machine** dropdown lists each VM and **All VMs**. Nothing is selected at first, and **Edit** stays disabled until a choice is made. With one VM selected the tab shows that VM's attributes and a saved change applies to that VM only. With **All VMs** the tab shows the attributes of the primary VM (the one with the lowest workload id) and a saved change is applied to every VM. A VM that fails does not stop the others, and the failures are reported. An instance with one VM has no dropdown.
- On the **server page** the tab acts on that one VM only.
- The tab appears only for VMware clouds (vmware, vsphere or esxi). On the server page it is hidden for servers that are not a vCenter VM.
- Access is controlled by a Morpheus role permission (none, read, full), the same for both tabs.

## Requirements
- Morpheus appliance (manifest minimum version 6.3.0) with a VMware vCenter cloud integration.
- vCenter account with permission to manage and set custom attributes.
- To build: JDK 11 or later. Gradle is included as `gradlew`.

## Build
```
.\gradlew clean build shadowJar
```
The plugin jar is `build/libs/<project name>-<version>-all.jar`. The latest released jar is also kept in the [plugin jar](plugin%20jar) folder of this repository.

## Install
1. In Morpheus, go to **Administration > Integrations > Plugins** and upload the `-all.jar` from the [plugin jar](plugin%20jar) folder (or the one you built).
2. Sign out and back in so the new permission appears in your session.
3. Go to **Administration > Roles**, open a role, and set **Custom Attributes Tab** to `Read` or `Full`.

## Configuration

### Role permission
| Permission | Code | Levels |
|---|---|---|
| Custom Attributes Tab | `customAttributes-tab` | `none` hides the tab. `read` shows it view-only. `full` allows add, edit and clear. |

### vCenter connection
The plugin uses the vCenter URL and credentials of the instance's Morpheus cloud integration. It looks for credentials in this order:
1. The cloud's account credential data.
2. The credentials stored for that cloud.
3. The cloud's configuration map.
4. The cloud's service username and password.

If the cloud does not provide complete credentials, the plugin falls back to the plugin settings (**Administration > Integrations > Plugins > Custom Attributes Plugin > Settings**):

| Setting | Purpose |
|---|---|
| vCenter Username (fallback) | vCenter service account, used only for the cloud's own vCenter host |
| vCenter Password (fallback) | Password for that account |
| Allow untrusted vCenter certificate | Off by default. Accepts a self-signed certificate and shows a warning in the tab |

The plugin does not store credentials itself and does not log passwords.

## Usage
1. Open an instance or a server on a VMware cloud and select the **Custom Attributes** tab.
2. Select **Edit**. Change a value in the list, or enter an attribute name, value and type and select **ADD**. Select **SAVE** to apply all changes, or **CANCEL** to discard them. The **Edit** button is shown only to users with the `full` permission.

The plugin finds each VM in vCenter by the VM reference (for example `vm-4135`) that Morpheus stores as the external id of the VM's server. It does not look VMs up by name, so renaming a VM does not affect the tab. If any VM has no usable reference, the tab shows an error and makes no change.

## How it works
```
Browser -> Morpheus (instance or server page) -> Custom Attributes plugin -> vCenter (HTTPS, SOAP)
```
- The tab is built on the server. For an instance with several VMs the attributes of the picked VM are loaded with `GET|POST /plugin/customAttributes/vmData` (`instanceId`, `target`). Changes use `POST /plugin/customAttributes/setAttr` (exactly one of `instanceId` or `serverId`, `target` for an instance with several VMs, attribute name, value and type) once per changed attribute, with the Morpheus CSRF token and the `X-Requested-With` header. `target` is a server id of that instance or `all`, and the plugin rejects any other value. If Morpheus rejects the `POST`, the call is repeated once as `GET`. `setAttr` requires the `full` permission and access to the instance's or server's account; `vmData` requires `read`. The `clearAttr` route is still available but the tab no longer uses it.
- For each request the plugin reads the credentials from Morpheus, logs in to vCenter once, makes the change on every VM, and logs out.

## Known limitations
- The vCenter certificate must be trusted unless **Allow untrusted vCenter certificate** is turned on in the plugin settings, in which case the tab shows a warning.
- An instance may have at most 25 VMs.

## Third-party software
The jar bundles `slf4j-api` (MIT). The license text and copyright notice are in [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) and inside the jar under `META-INF/`. Groovy and the Morpheus plugin API are provided by Morpheus and are not bundled.

## Versioning and branches
Versions follow MAJOR.MINOR.PATCH and are set in `gradle.properties`. The jar of the latest release is kept in the `plugin jar` folder.
