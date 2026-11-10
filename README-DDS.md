# Fast DDS / ROS 2 Lyrical Luth interop notes

Java/UI side of a DDS participant that talks to **ROS 2 Lyrical Luth** (`rmw_fastrtps`).

- ROS topic `android_chatter` (i.e. `/android_chatter`) &rarr; DDS topic **`rt/android_chatter`**
  (rmw drops the leading `/` and adds the ROS-to-DDS `rt/` prefix).
- DDS type name **`std_msgs::msg::dds_::String_`** (what Fast DDS-Gen emits with `-typeros2`).

Both strings live in [`DdsManager`](app/src/main/java/com/example/fastddstest/DdsManager.java) as
`DDS_TOPIC` / `DDS_TYPE_NAME`. If either is wrong the endpoints never match, even though both sides
"look" correct.

## Pinned versions

These are the versions ROS 2 Lyrical Luth generates and links against — do not mix in a different
minor line:

| Component | Version |
| --- | --- |
| Fast DDS | v3.6.2 |
| Fast CDR | v2.3.6 |
| foonathan_memory_vendor | 1.4.1 |
| Fast DDS-Gen | 4.3.x |

Nothing prebuilt is committed: `app/src/main/cpp/` builds Fast CDR, foonathan_memory and Fast DDS
from source through `ExternalProject`, so the **first native build takes roughly 25–45 minutes per
ABI**. Subsequent builds reuse the CMake output.

## Required SDK packages

```bash
sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"
```

`cmake;4.1.2` (i.e. `version = "4.1.2"` in the `externalNativeBuild`) is required because the
upstream Fast DDS / Fast CDR CMakeLists still declare `cmake_minimum_required(VERSION 3.5)`-era
policy assumptions. CMake 4.x hard-errors on those, so the build also passes
`-DCMAKE_POLICY_VERSION_MINIMUM=3.5` to keep the old policy behaviour. Changing either the CMake
version or dropping that flag breaks the configure step.

## Building

```bash
./gradlew :app:generateDdsIdl          # (re)generate the std_msgs String type support
./gradlew :app:assembleDebug           # full build, all ABIs, 25-45 min the first time
./gradlew :app:assembleDebug -PddsAbi=arm64-v8a   # iterate on a physical phone
./gradlew :app:assembleDebug -PddsAbi=x86_64      # iterate on an x86_64 AVD
```

`-PddsAbi=...` restricts the native build to one ABI, which is the only sane way to iterate: a
one-line C++ change otherwise costs a full multi-ABI rebuild.

## Discovery modes

**SIMPLE and Discovery Server do not interoperate.** Every participant in the domain — the phone,
the AVD, the host `ros2` process — must use the *same* mode, and in Discovery Server mode every
participant must point at the *same* server. Mixed modes silently discover nothing.

| Where the app runs | App setting | Host side |
| --- | --- | --- |
| Phone on the host's Wi-Fi | `SIMPLE`, host/port ignored | nothing extra; plain `ros2` defaults |
| AVD (emulator) | `DISCOVERY_SERVER`, host `10.0.2.2`, port `11811` | run a Discovery Server on the host LAN IP |
| Phone on a different network / hotspot | not supported by SIMPLE (no multicast across subnets) | `DISCOVERY_SERVER` with a reachable server |

The AVD case needs Discovery Server because **emulator NAT does not carry multicast** — the phone
side can send but never receives the SIMPLE discovery announcements.

Host side, once:

```bash
fastdds discovery -i 0 -l <host-lan-ip> -p 11811
export ROS_DISCOVERY_SERVER=<host-lan-ip>:11811
```

Keep `ROS_DISCOVERY_SERVER` exported in every shell that runs `ros2` commands, and remember that in
Discovery Server mode the server must be started *before* the participants.

## Verifying

A raw Fast DDS participant is **not** an rmw participant, so it has no ROS node graph information:
`ros2 node list` will stay empty even when everything works. Verify against the wire instead:

```bash
ros2 topic echo /android_chatter                                   # phone -> host
ros2 topic pub /android_chatter std_msgs/msg/String "{data: 'hi'}" # host -> phone, once
```

App-side symptoms:

- `Discovered peers: 0` every 5 s &rarr; wrong mode, wrong server, or the multicast lock /
  Wi-Fi is off. `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_WIFI_MULTICAST_STATE` are all
  declared in the manifest; the manager holds a `WifiManager.MulticastLock` named `dds` for the
  lifetime of the participant.
- Peers > 0 but no samples &rarr; topic or type name mismatch (the endpoint matched, the type did
  not): check `rt/android_chatter` and `std_msgs::msg::dds_::String_`.
