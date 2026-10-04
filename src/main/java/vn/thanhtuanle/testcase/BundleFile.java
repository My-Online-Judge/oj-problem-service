package vn.thanhtuanle.testcase;

import java.util.Comparator;
import java.util.List;

/** One file inside a judge bundle: {@code 1.in}, {@code 1.out}, ..., and {@code info}. */
public record BundleFile(String name, byte[] content) {

    /** Bundles are hashed and zipped in file-name order, so equal contents always give the same hash. */
    static List<BundleFile> sortedByName(List<BundleFile> files) {
        return files.stream().sorted(Comparator.comparing(BundleFile::name)).toList();
    }
}
