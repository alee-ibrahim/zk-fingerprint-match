package io.github.aleeibrahim.zkmatch.gallery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Templates from a folder, for trying the service without a CVSecurity server. Each file
 * {@code <id>_<finger>.<ext>} holds one base64 template: {@code .ss21} or {@code .zk10} for
 * ZK 10.0, {@code .zk12} for 12.0 and {@code .zk13} for 13.0.
 */
public final class DirectorySource implements GallerySource {
    private static final Pattern NAME = Pattern.compile("(.+)_(\\d+)\\.(ss21|zk10|zk12|zk13)");
    private final Path dir;

    public DirectorySource(Path dir) {
        this.dir = dir;
    }

    @Override
    public String name() {
        return "directory";
    }

    @Override
    public void load(Consumer<StoredTemplate> sink) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                Matcher m = NAME.matcher(f.getFileName().toString());
                if (!m.matches()) continue;
                String version = m.group(3).equals("ss21") ? "10" : m.group(3).substring(2);
                sink.accept(new StoredTemplate(m.group(1), Integer.parseInt(m.group(2)), version, Files.readString(f).trim()));
            }
        }
    }
}
