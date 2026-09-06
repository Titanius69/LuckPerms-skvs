# LuckPerms SKVS Storage Backend

## What this adds

New storage method **`skvs`** for LuckPerms (works on **Bukkit/Paper** and **Velocity** – both use the `common` module).

## Config

1. Shared SKVS file (same as other SKVS plugins):

```
plugins/.skvs/config.txt
```

```
host=127.0.0.1
port=3000
secret_key=your-secret
database=mc_data
use_https=false
```

2. In LuckPerms `config.yml`:

```yaml
storage-method: skvs
```

No separate LuckPerms SKVS section – only `plugins/.skvs/config.txt`.

## Tables (hardcoded)

| Table | Purpose |
|-------|---------|
| `lp_users` | uuid, username, primary_group, nodes_json |
| `lp_groups` | name, nodes_json |
| `lp_tracks` | name, groups_json |
| `lp_uuid` | uuid ↔ username cache |
| `lp_actions` | simple action log |

## Files to merge into LuckPerms source

```
common/src/main/java/me/lucko/luckperms/common/storage/
  StorageType.java          (modified – added SKVS)
  StorageFactory.java       (modified – case SKVS)
  implementation/skvs/
    MiniJson.java
    SkvsClient.java
    SkvsConnectionConfig.java
    SkvsStorage.java
```

## Build

```bash
./gradlew build
```

Use the Bukkit and/or Velocity jars from the build output.

## Notes

- Permissions/groups/tracks/uuid cache are fully stored in SKVS.
- Action log is minimal (writes; page query returns empty – not critical for sync).
- Bulk update is supported for users and groups.
- Multi-server: all nodes with `storage-method: skvs` and the same `config.txt` share data.
