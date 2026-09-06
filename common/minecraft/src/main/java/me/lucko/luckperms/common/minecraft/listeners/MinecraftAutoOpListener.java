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

package me.lucko.luckperms.common.minecraft.listeners;

import me.lucko.luckperms.common.event.listeners.AbstractAutoOpListener;
import me.lucko.luckperms.common.minecraft.MinecraftLuckPermsPlugin;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class MinecraftAutoOpListener extends AbstractAutoOpListener<MinecraftLuckPermsPlugin<?, ?>, ServerPlayer> {
    public MinecraftAutoOpListener(MinecraftLuckPermsPlugin<?, ?> plugin) {
        super(plugin, plugin.getContextManager(), ServerPlayer.class);
    }

    @Override
    protected boolean isServerAvailable() {
        return this.plugin.getBootstrap().getServer().isPresent();
    }

    @Override
    protected UUID getUniqueId(ServerPlayer player) {
        return player.getUUID();
    }

    @Override
    protected void setOp(ServerPlayer player, boolean value, boolean callerIsSync) {
        if (callerIsSync) {
            setOp(player, value);
        } else {
            this.plugin.getBootstrap().getScheduler().executeSync(() -> setOp(player, value));
        }
    }

    private void setOp(ServerPlayer player, boolean value) {
        this.plugin.getBootstrap().getServer().ifPresent(server -> {
            if (value) {
                server.getPlayerList().op(player.nameAndId());
            } else {
                server.getPlayerList().deop(player.nameAndId());
            }
        });
    }

}
