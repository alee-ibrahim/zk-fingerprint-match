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
 * {@code <id>_<finger>.ss21} holds one base64 ZK 10.0 template.
 */
public final class DirectorySource implements GallerySource {
    private static final Pattern NAME = Pattern.compile("(.+)_(\\d+)\\.ss21");
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
                sink.accept(new StoredTemplate(m.group(1), Integer.parseInt(m.group(2)), "10", Files.readString(f).trim()));
            }
        }
    }
}
