/*
 * The MIT License (MIT)
 *
 * Copyright © 2026 - OpenLogin Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.nickuc.openlogin.bukkit.ui.chat.impl;

import com.nickuc.openlogin.bukkit.enums.BukkitVersion;
import com.nickuc.openlogin.bukkit.ui.chat.ActionbarAPI;
import com.nickuc.openlogin.bukkit.util.BukkitReflection;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public class PacketActionbarImpl implements ActionbarAPI {

    private final UUID EMPTY_UUID = new UUID(0L, 0L);

    private final Object typeMessage;
    private final Constructor<?> chatConstructor;
    private final boolean v1_16;

    public PacketActionbarImpl() throws ReflectiveOperationException {
        BukkitVersion bukkitVersion = BukkitVersion.getVersion();

        Class<?> typeMessageClass;
        if (bukkitVersion.isNewerOrEqual(BukkitVersion.v1_12)) {
            typeMessageClass = BukkitReflection.getClass("net.minecraft.network.chat.ChatMessageType", "{nms}.ChatMessageType");
            if (typeMessageClass == null) throw new IllegalArgumentException("Cannot find net.minecraft.network.chat.ChatMessageType!");

            typeMessage = Optional.of(typeMessageClass.getEnumConstants())
                    .filter(t -> t.length >= 3)
                    .map(t -> t[2])
                    .orElseThrow(() -> new IllegalStateException("Could not find ACTIONBAR type message"));
        } else {
            typeMessageClass = byte.class;
            typeMessage = (byte) 2;
        }

        Class<?> packetPlayOutChat = BukkitReflection.getClass("net.minecraft.network.protocol.game.PacketPlayOutChat", "{nms}.PacketPlayOutChat");
        if (packetPlayOutChat == null) throw new IllegalArgumentException("Cannot find net.minecraft.network.protocol.game.PacketPlayOutChat!");

        v1_16 = bukkitVersion.isNewerOrEqual(BukkitVersion.v1_16);
        chatConstructor = v1_16
                ? packetPlayOutChat.getConstructor(Chat.CHAT_BASE_COMPONENT, typeMessageClass, UUID.class)
                : packetPlayOutChat.getConstructor(Chat.CHAT_BASE_COMPONENT, typeMessageClass);
    }

    @Override
    public void send(Player player, String message) {
        try {
            Object chatMessage = Chat.fromJson("{\"text\":\"" + message + "\"}");
            Object packet = v1_16 ?
                    chatConstructor.newInstance(chatMessage, typeMessage, EMPTY_UUID) :
                    chatConstructor.newInstance(chatMessage, typeMessage);
            BukkitReflection.sendPacket(player, packet);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not send actionbar for " + player.getName() + "!", e);
        }
    }

    private static class Chat {

        public static final Class<?> CHAT_BASE_COMPONENT;
        private static final Method COMPONENT_FROM_JSON;

        static {
            Class<?> chatBaseComponent;
            Method componentFromJson;
            try {
                if ((chatBaseComponent = BukkitReflection.getClass("net.minecraft.network.chat.IChatBaseComponent", "{nms}.IChatBaseComponent")) == null) {
                    throw new IllegalArgumentException("Cannot find net.minecraft.network.chat.IChatBaseComponent!");
                }

                Class<?>[] icbcDeclaredClasses = chatBaseComponent.getDeclaredClasses();
                componentFromJson = icbcDeclaredClasses.length > 0
                        ? icbcDeclaredClasses[0].getMethod("a", String.class)
                        : BukkitReflection.getClass("{nms}.ChatSerializer").getMethod("a", String.class);
            } catch (Throwable throwable) {
                chatBaseComponent = null;
                componentFromJson = null;
            }

            CHAT_BASE_COMPONENT = chatBaseComponent;
            COMPONENT_FROM_JSON = componentFromJson;
        }

        public static Object fromJson(String json) {
            if (COMPONENT_FROM_JSON == null) throw new IllegalStateException("ChatComponent api not loaded!");

            try {
                return COMPONENT_FROM_JSON.invoke(null, json);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException("Could not serialize ChatComponent! \"" + json + "\"", e);
            }
        }
    }
}
