/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.storage.implementation.skvs;

import me.lucko.luckperms.common.actionlog.LogPage;
import me.lucko.luckperms.common.bulkupdate.BulkUpdate;
import me.lucko.luckperms.common.model.HolderType;
import me.lucko.luckperms.common.filter.FilterList;
import me.lucko.luckperms.common.filter.PageParameters;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.Track;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.model.manager.group.GroupManager;
import me.lucko.luckperms.common.node.factory.NodeBuilders;
import me.lucko.luckperms.common.node.matcher.ConstraintNodeMatcher;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.storage.StorageMetadata;
import me.lucko.luckperms.common.storage.implementation.StorageImplementation;
import me.lucko.luckperms.common.storage.implementation.skvs.SkvsClient.QueryResult;
import me.lucko.luckperms.common.storage.implementation.skvs.SkvsClient.SkvsQueryException;
import me.lucko.luckperms.common.storage.misc.NodeEntry;
import me.lucko.luckperms.common.storage.misc.PlayerSaveResultImpl;
import net.luckperms.api.actionlog.Action;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.PlayerSaveResult;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * LuckPerms storage backend backed by SKVS SQL over HTTP.
 * Connection settings are read exclusively from {@code plugins/.skvs/config.txt}.
 * <p>
 * Tables (hardcoded names):
 * <ul>
 *   <li>{@code lp_users} – uuid, username, primary_group, nodes_json</li>
 *   <li>{@code lp_groups} – name, nodes_json</li>
 *   <li>{@code lp_tracks} – name, groups_json</li>
 *   <li>{@code lp_uuid} – uuid, username</li>
 *   <li>{@code lp_actions} – id, timestamp, source, target, description</li>
 * </ul>
 */
public class SkvsStorage implements StorageImplementation {

    private final LuckPermsPlugin plugin;
    private SkvsClient client;
    private SkvsConnectionConfig config;

    public SkvsStorage(LuckPermsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public LuckPermsPlugin getPlugin() {
        return this.plugin;
    }

    @Override
    public String getImplementationName() {
        return "SKVS";
    }

    @Override
    public void init() throws Exception {
        Path dataDir = this.plugin.getBootstrap().getDataDirectory();
        Path pluginsFolder = dataDir.getParent();
        this.config = SkvsConnectionConfig.load(pluginsFolder);
        this.client = new SkvsClient(this.config, 5000, 30000);

        // Create tables
        this.client.query(
                "CREATE TABLE IF NOT EXISTS lp_users (" +
                        "uuid TEXT PRIMARY KEY NOT NULL, " +
                        "username TEXT, " +
                        "primary_group TEXT, " +
                        "nodes_json TEXT NOT NULL" +
                        ")",
                Collections.emptyList());
        this.client.query(
                "CREATE TABLE IF NOT EXISTS lp_groups (" +
                        "name TEXT PRIMARY KEY NOT NULL, " +
                        "nodes_json TEXT NOT NULL" +
                        ")",
                Collections.emptyList());
        this.client.query(
                "CREATE TABLE IF NOT EXISTS lp_tracks (" +
                        "name TEXT PRIMARY KEY NOT NULL, " +
                        "groups_json TEXT NOT NULL" +
                        ")",
                Collections.emptyList());
        this.client.query(
                "CREATE TABLE IF NOT EXISTS lp_uuid (" +
                        "uuid TEXT PRIMARY KEY NOT NULL, " +
                        "username TEXT NOT NULL" +
                        ")",
                Collections.emptyList());
        this.client.query(
                "CREATE TABLE IF NOT EXISTS lp_actions (" +
                        "id INTEGER PRIMARY KEY, " +
                        "timestamp INTEGER NOT NULL, " +
                        "source TEXT, " +
                        "target TEXT, " +
                        "description TEXT" +
                        ")",
                Collections.emptyList());

        this.plugin.getLogger().info("SKVS storage connected to " + this.config.baseUrl()
                + " database=" + this.config.database());
    }

    @Override
    public void shutdown() {
        this.client = null;
    }

    @Override
    public StorageMetadata getMeta() {
        StorageMetadata meta = new StorageMetadata();
        long start = System.currentTimeMillis();
        boolean ok = false;
        try {
            if (this.client != null) {
                this.client.query("SELECT 1 AS ok", Collections.emptyList());
                ok = true;
            }
        } catch (Exception ignored) {
            ok = false;
        }
        meta.connected(ok);
        if (ok) {
            meta.ping((int) (System.currentTimeMillis() - start));
        }
        return meta;
    }

    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    @Override
    public User loadUser(UUID uniqueId, String username) throws Exception {
        User user = this.plugin.getUserManager().getOrMake(uniqueId, username);
        List<Map<String, Object>> rows = this.client.query(
                "SELECT username, primary_group, nodes_json FROM lp_users WHERE uuid = ?",
                Collections.singletonList(uniqueId.toString()));

        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            String name = str(row.get("username"));
            String primaryGroup = str(row.get("primary_group"));
            String nodesJson = str(row.get("nodes_json"));

            user.getPrimaryGroup().setStoredValue(primaryGroup);
            if (name != null) {
                user.setUsername(name, true);
            }
            user.loadNodesFromStorage(deserializeNodes(nodesJson));
            this.plugin.getUserManager().giveDefaultIfNeeded(user);

            if (user.auditTemporaryNodes()) {
                saveUser(user);
            }
        } else {
            if (this.plugin.getUserManager().isNonDefaultUser(user)) {
                user.loadNodesFromStorage(Collections.emptyList());
                user.getPrimaryGroup().setStoredValue(null);
                this.plugin.getUserManager().giveDefaultIfNeeded(user);
            }
        }
        return user;
    }

    @Override
    public Map<UUID, User> loadUsers(Set<UUID> uniqueIds) throws Exception {
        Map<UUID, User> map = new HashMap<>();
        for (UUID id : uniqueIds) {
            map.put(id, loadUser(id, null));
        }
        return map;
    }

    @Override
    public void saveUser(User user) throws Exception {
        user.normalData().discardChanges();
        if (!this.plugin.getUserManager().isNonDefaultUser(user)) {
            this.client.query("DELETE FROM lp_users WHERE uuid = ?",
                    Collections.singletonList(user.getUniqueId().toString()));
            return;
        }

        String name = user.getUsername().orElse("null");
        String primaryGroup = user.getPrimaryGroup().getStoredValue().orElse(GroupManager.DEFAULT_GROUP_NAME);
        String nodesJson = serializeNodes(user.normalData().asList());

        // upsert
        QueryResult ur = this.client.queryFull(
                "UPDATE lp_users SET username = ?, primary_group = ?, nodes_json = ? WHERE uuid = ?",
                list(name, primaryGroup, nodesJson, user.getUniqueId().toString()));
        if (ur.affectedRows() == null || ur.affectedRows() == 0L) {
            this.client.query(
                    "INSERT INTO lp_users (uuid, username, primary_group, nodes_json) VALUES (?, ?, ?, ?)",
                    list(user.getUniqueId().toString(), name, primaryGroup, nodesJson));
        }
    }

    @Override
    public Set<UUID> getUniqueUsers() throws Exception {
        List<Map<String, Object>> rows = this.client.query("SELECT uuid FROM lp_users", Collections.emptyList());
        Set<UUID> set = new HashSet<>();
        for (Map<String, Object> row : rows) {
            try {
                set.add(UUID.fromString(str(row.get("uuid"))));
            } catch (Exception ignored) {
            }
        }
        return set;
    }

    @Override
    public <N extends Node> List<NodeEntry<UUID, N>> searchUserNodes(ConstraintNodeMatcher<N> constraint) throws Exception {
        List<NodeEntry<UUID, N>> results = new ArrayList<>();
        List<Map<String, Object>> rows = this.client.query("SELECT uuid, nodes_json FROM lp_users", Collections.emptyList());
        for (Map<String, Object> row : rows) {
            UUID uuid;
            try {
                uuid = UUID.fromString(str(row.get("uuid")));
            } catch (Exception e) {
                continue;
            }
            for (Node node : deserializeNodes(str(row.get("nodes_json")))) {
                N match = constraint.filterConstraintMatch(node);
                if (match != null) {
                    results.add(NodeEntry.of(uuid, match));
                }
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Groups
    // -------------------------------------------------------------------------

    @Override
    public Group createAndLoadGroup(String name) throws Exception {
        Group group = this.plugin.getGroupManager().getOrMake(name);
        loadGroupInto(group);
        return group;
    }

    @Override
    public Optional<Group> loadGroup(String name) throws Exception {
        Group group = this.plugin.getGroupManager().getIfLoaded(name);
        if (group != null) {
            loadGroupInto(group);
            return Optional.of(group);
        }
        List<Map<String, Object>> rows = this.client.query(
                "SELECT name FROM lp_groups WHERE name = ?", Collections.singletonList(name));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        group = this.plugin.getGroupManager().getOrMake(name);
        loadGroupInto(group);
        return Optional.of(group);
    }

    private void loadGroupInto(Group group) throws Exception {
        List<Map<String, Object>> rows = this.client.query(
                "SELECT nodes_json FROM lp_groups WHERE name = ?",
                Collections.singletonList(group.getName()));
        if (!rows.isEmpty()) {
            group.loadNodesFromStorage(deserializeNodes(str(rows.get(0).get("nodes_json"))));
        } else {
            // ensure row exists for new groups
            this.client.query(
                    "INSERT INTO lp_groups (name, nodes_json) VALUES (?, ?)",
                    list(group.getName(), "[]"));
            group.loadNodesFromStorage(Collections.emptyList());
        }
    }

    @Override
    public void loadAllGroups() throws Exception {
        List<Map<String, Object>> rows = this.client.query("SELECT name, nodes_json FROM lp_groups", Collections.emptyList());
        Set<String> found = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String name = str(row.get("name"));
            found.add(name.toLowerCase(Locale.ROOT));
            Group group = this.plugin.getGroupManager().getOrMake(name);
            group.loadNodesFromStorage(deserializeNodes(str(row.get("nodes_json"))));
        }
        this.plugin.getGroupManager().retainAll(found);
    }

    @Override
    public void saveGroup(Group group) throws Exception {
        group.normalData().discardChanges();
        String nodesJson = serializeNodes(group.normalData().asList());
        QueryResult ur = this.client.queryFull(
                "UPDATE lp_groups SET nodes_json = ? WHERE name = ?",
                list(nodesJson, group.getName()));
        if (ur.affectedRows() == null || ur.affectedRows() == 0L) {
            this.client.query(
                    "INSERT INTO lp_groups (name, nodes_json) VALUES (?, ?)",
                    list(group.getName(), nodesJson));
        }
    }

    @Override
    public void deleteGroup(Group group) throws Exception {
        this.client.query("DELETE FROM lp_groups WHERE name = ?",
                Collections.singletonList(group.getName()));
    }

    @Override
    public <N extends Node> List<NodeEntry<String, N>> searchGroupNodes(ConstraintNodeMatcher<N> constraint) throws Exception {
        List<NodeEntry<String, N>> results = new ArrayList<>();
        List<Map<String, Object>> rows = this.client.query("SELECT name, nodes_json FROM lp_groups", Collections.emptyList());
        for (Map<String, Object> row : rows) {
            String name = str(row.get("name"));
            for (Node node : deserializeNodes(str(row.get("nodes_json")))) {
                N match = constraint.filterConstraintMatch(node);
                if (match != null) {
                    results.add(NodeEntry.of(name, match));
                }
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Tracks
    // -------------------------------------------------------------------------

    @Override
    public Track createAndLoadTrack(String name) throws Exception {
        Track track = this.plugin.getTrackManager().getOrMake(name);
        loadTrackInto(track);
        return track;
    }

    @Override
    public Optional<Track> loadTrack(String name) throws Exception {
        List<Map<String, Object>> rows = this.client.query(
                "SELECT name FROM lp_tracks WHERE name = ?", Collections.singletonList(name));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Track track = this.plugin.getTrackManager().getOrMake(name);
        loadTrackInto(track);
        return Optional.of(track);
    }

    private void loadTrackInto(Track track) throws Exception {
        List<Map<String, Object>> rows = this.client.query(
                "SELECT groups_json FROM lp_tracks WHERE name = ?",
                Collections.singletonList(track.getName()));
        if (!rows.isEmpty()) {
            track.setGroups(deserializeStringList(str(rows.get(0).get("groups_json"))));
        } else {
            this.client.query(
                    "INSERT INTO lp_tracks (name, groups_json) VALUES (?, ?)",
                    list(track.getName(), "[]"));
            track.setGroups(Collections.emptyList());
        }
    }

    @Override
    public void loadAllTracks() throws Exception {
        List<Map<String, Object>> rows = this.client.query("SELECT name, groups_json FROM lp_tracks", Collections.emptyList());
        Set<String> found = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String name = str(row.get("name"));
            found.add(name.toLowerCase(Locale.ROOT));
            Track track = this.plugin.getTrackManager().getOrMake(name);
            track.setGroups(deserializeStringList(str(row.get("groups_json"))));
        }
        this.plugin.getTrackManager().retainAll(found);
    }

    @Override
    public void saveTrack(Track track) throws Exception {
        String groupsJson = serializeStringList(track.getGroups());
        QueryResult ur = this.client.queryFull(
                "UPDATE lp_tracks SET groups_json = ? WHERE name = ?",
                list(groupsJson, track.getName()));
        if (ur.affectedRows() == null || ur.affectedRows() == 0L) {
            this.client.query(
                    "INSERT INTO lp_tracks (name, groups_json) VALUES (?, ?)",
                    list(track.getName(), groupsJson));
        }
    }

    @Override
    public void deleteTrack(Track track) throws Exception {
        this.client.query("DELETE FROM lp_tracks WHERE name = ?",
                Collections.singletonList(track.getName()));
    }

    // -------------------------------------------------------------------------
    // UUID cache
    // -------------------------------------------------------------------------

    @Override
    public PlayerSaveResult savePlayerData(UUID uniqueId, String username) throws Exception {
        String oldUsername = getPlayerName(uniqueId);
        PlayerSaveResultImpl result = PlayerSaveResultImpl.determineBaseResult(username, oldUsername);

        // upsert uuid mapping
        QueryResult ur = this.client.queryFull(
                "UPDATE lp_uuid SET username = ? WHERE uuid = ?",
                list(username, uniqueId.toString()));
        if (ur.affectedRows() == null || ur.affectedRows() == 0L) {
            this.client.query(
                    "INSERT INTO lp_uuid (uuid, username) VALUES (?, ?)",
                    list(uniqueId.toString(), username));
        }

        // look for other uuids with same name
        List<Map<String, Object>> others = this.client.query(
                "SELECT uuid FROM lp_uuid WHERE username = ? AND uuid != ?",
                list(username, uniqueId.toString()));
        if (!others.isEmpty()) {
            Set<UUID> otherIds = new HashSet<>();
            for (Map<String, Object> row : others) {
                try {
                    otherIds.add(UUID.fromString(str(row.get("uuid"))));
                } catch (Exception ignored) {
                }
            }
            if (!otherIds.isEmpty()) {
                result = result.withOtherUuidsPresent(otherIds);
            }
        }
        return result;
    }

    @Override
    public void deletePlayerData(UUID uniqueId) throws Exception {
        this.client.query("DELETE FROM lp_uuid WHERE uuid = ?",
                Collections.singletonList(uniqueId.toString()));
    }

    @Override
    public @Nullable UUID getPlayerUniqueId(String username) throws Exception {
        List<Map<String, Object>> rows = this.client.query(
                "SELECT uuid FROM lp_uuid WHERE username = ?",
                Collections.singletonList(username));
        if (rows.isEmpty()) {
            // also try case-insensitive via scanning (SKVS may not have LOWER())
            rows = this.client.query("SELECT uuid, username FROM lp_uuid", Collections.emptyList());
            for (Map<String, Object> row : rows) {
                if (username.equalsIgnoreCase(str(row.get("username")))) {
                    return UUID.fromString(str(row.get("uuid")));
                }
            }
            return null;
        }
        return UUID.fromString(str(rows.get(0).get("uuid")));
    }

    @Override
    public @Nullable String getPlayerName(UUID uniqueId) throws Exception {
        List<Map<String, Object>> rows = this.client.query(
                "SELECT username FROM lp_uuid WHERE uuid = ?",
                Collections.singletonList(uniqueId.toString()));
        if (rows.isEmpty()) {
            return null;
        }
        return str(rows.get(0).get("username"));
    }

    // -------------------------------------------------------------------------
    // Action log (minimal)
    // -------------------------------------------------------------------------

    @Override
    public void logAction(Action entry) throws Exception {
        long id = System.currentTimeMillis(); // simple unique-ish id
        this.client.query(
                "INSERT INTO lp_actions (id, timestamp, source, target, description) VALUES (?, ?, ?, ?, ?)",
                list(id,
                        entry.getTimestamp().toEpochMilli(),
                        entry.getSource().getName(),
                        entry.getTarget().getName(),
                        entry.getDescription()));
    }

    @Override
    public LogPage getLogPage(FilterList<Action> filters, @Nullable PageParameters page) throws Exception {
        // Minimal: return empty page – full action-log filtering is complex; core perms still work.
        return LogPage.of(Collections.emptyList(), page, 0);
    }

    @Override
    public void applyBulkUpdate(BulkUpdate bulkUpdate) throws Exception {
        // Apply to all users
        for (UUID uuid : getUniqueUsers()) {
            User user = loadUser(uuid, null);
            Set<Node> nodes = new HashSet<>(user.normalData().asList());
            Set<Node> updated = bulkUpdate.apply(nodes, HolderType.USER);
            if (updated != null) {
                user.loadNodesFromStorage(updated);
                saveUser(user);
            }
        }
        // Groups
        List<Map<String, Object>> groups = this.client.query("SELECT name FROM lp_groups", Collections.emptyList());
        for (Map<String, Object> row : groups) {
            Optional<Group> g = loadGroup(str(row.get("name")));
            if (g.isPresent()) {
                Group group = g.get();
                Set<Node> nodes = new HashSet<>(group.normalData().asList());
                Set<Node> updated = bulkUpdate.apply(nodes, HolderType.GROUP);
                if (updated != null) {
                    group.loadNodesFromStorage(updated);
                    saveGroup(group);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Node JSON helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String serializeNodes(List<Node> nodes) {
        List<Object> list = new ArrayList<>();
        for (Node node : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", node.getKey());
            m.put("value", node.getValue());
            if (node.hasExpiry()) {
                m.put("expiry", node.getExpiry().getEpochSecond());
            }
            if (!node.getContexts().isEmpty()) {
                // ContextSet has toMap(): Map<String, Set<String>> — not BiConsumer forEach
                Map<String, List<String>> ctx = new LinkedHashMap<>();
                for (Map.Entry<String, java.util.Set<String>> e : node.getContexts().toMap().entrySet()) {
                    ctx.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
                m.put("context", ctx);
            }
            list.add(m);
        }
        return MiniJson.writeValue(list);
    }

    @SuppressWarnings("unchecked")
    private static List<Node> deserializeNodes(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return Collections.emptyList();
        }
        List<Node> nodes = new ArrayList<>();
        try {
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof List)) {
                return nodes;
            }
            for (Object o : (List<?>) parsed) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) o;
                String key = str(m.get("key"));
                if (key == null) {
                    continue;
                }
                boolean value = true;
                Object v = m.get("value");
                if (v instanceof Boolean) {
                    value = (Boolean) v;
                } else if (v != null) {
                    value = Boolean.parseBoolean(v.toString());
                }

                NodeBuilder<?, ?> builder = NodeBuilders.determineMostApplicable(key).value(value);

                Object exp = m.get("expiry");
                if (exp instanceof Number) {
                    builder.expiry(Instant.ofEpochSecond(((Number) exp).longValue()));
                }

                Object ctxObj = m.get("context");
                if (ctxObj instanceof Map) {
                    ImmutableContextSet.Builder ctx = ImmutableContextSet.builder();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) ctxObj).entrySet()) {
                        String ck = String.valueOf(e.getKey());
                        Object cv = e.getValue();
                        if (cv instanceof List) {
                            for (Object item : (List<?>) cv) {
                                ctx.add(ck, String.valueOf(item));
                            }
                        } else if (cv != null) {
                            ctx.add(ck, String.valueOf(cv));
                        }
                    }
                    builder.context(ctx.build());
                }
                nodes.add(builder.build());
            }
        } catch (Exception e) {
            // return what we have
        }
        return nodes;
    }

    private static String serializeStringList(List<String> groups) {
        return MiniJson.writeValue(groups == null ? Collections.emptyList() : groups);
    }

    @SuppressWarnings("unchecked")
    private static List<String> deserializeStringList(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof List)) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) parsed) {
                out.add(String.valueOf(o));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<Object> list(Object... objs) {
        List<Object> l = new ArrayList<>();
        Collections.addAll(l, objs);
        return l;
    }
}
