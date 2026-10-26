package com.local.yuanatlas;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 只把已授权根目录中的匹配图片或 ZIP 展平为列表，不移动文件、不返回文件夹。 */
final class FlatArchiveIndex {
    static List<File> scan(List<File> roots, BooleanSupplier cancelled) {
        return scan(roots, cancelled, false);
    }
    static List<File> scan(List<File> roots, BooleanSupplier cancelled, boolean images) {
        List<File> results = new ArrayList<>(); Set<String> visited = new HashSet<>();
        for (File root : roots) {
            try {
                File base = root.getCanonicalFile(); String boundary = base.getPath() + File.separator;
                ArrayDeque<File> queue = new ArrayDeque<>(); queue.add(base);
                while (!queue.isEmpty() && !cancelled.getAsBoolean()) {
                    File directory = queue.removeFirst();
                    String path = directory.getCanonicalPath();
                    // 防止符号链接越出授权根目录或循环；Android 私有数据目录不扫描。
                    if ((!path.equals(base.getPath()) && !path.startsWith(boundary)) || !visited.add(path)) continue;
                    File[] children = directory.listFiles(); if (children == null) continue;
                    for (File child : children) {
                        if (cancelled.getAsBoolean()) break;
                        if (child.isDirectory()) {
                            if ("Android".equals(child.getParentFile().getName()) && ("data".equals(child.getName()) || "obb".equals(child.getName()))) continue;
                            queue.addLast(child);
                        } else if (child.isFile() && matches(child.getName(), images)) {
                            String canonical = child.getCanonicalPath();
                            if (canonical.startsWith(boundary) && visited.add(canonical)) results.add(child.getCanonicalFile());
                        }
                    }
                }
            } catch (IOException | SecurityException ignored) { /* 无法访问的根目录不阻断其他已授权存储。 */ }
        }
        results.sort(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getPath));
        return results;
    }
    private static boolean matches(String name, boolean images) {
        String lower=name.toLowerCase(Locale.ROOT);
        return images ? lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".webp") : lower.endsWith(".zip");
    }
}
