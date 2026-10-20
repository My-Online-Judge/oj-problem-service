package vn.thanhtuanle.problem.packaging;

/** One test case read from a package: its name (the file stem), both files, and whether it is a sample. */
public record PackagedTestCase(String name, byte[] input, byte[] output, boolean sample) {
}
