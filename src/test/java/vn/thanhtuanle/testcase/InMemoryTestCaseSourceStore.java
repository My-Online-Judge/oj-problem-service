package vn.thanhtuanle.testcase;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A {@link TestCaseSourceStore} kept in a map, for unit tests. */
public class InMemoryTestCaseSourceStore implements TestCaseSourceStore {

    public final Map<String, byte[]> files = new HashMap<>();

    @Override
    public void put(String path, byte[] content) {
        files.put(path, content);
    }

    @Override
    public Optional<byte[]> get(String path) {
        return Optional.ofNullable(files.get(path));
    }

    @Override
    public boolean exists(String path) {
        return files.containsKey(path);
    }

    @Override
    public void deleteQuietly(String path) {
        files.remove(path);
    }
}
