package com.p2wn.diary.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.Base64;

public final class ItemIO {

    private ItemIO() {}

    public static String toBase64(ItemStack item) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             BukkitObjectOutputStream data = new BukkitObjectOutputStream(output)) {
            data.writeObject(item);
            data.flush();
            return Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    public static ItemStack fromBase64(String base64) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object obj = in.readObject();
            return (ItemStack) obj;
        } catch (ClassNotFoundException e) {
            throw new IOException("Class not found while reading ItemStack", e);
        }
    }
}
