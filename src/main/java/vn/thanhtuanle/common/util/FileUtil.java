package vn.thanhtuanle.common.util;

import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileUtil {

    public static Map<String, byte[]> extractZip(MultipartFile zipFile) throws IOException {
        Map<String, byte[]> extractedFiles = new HashMap<>();
        try (InputStream fis = zipFile.getInputStream();
                ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                if (!zipEntry.isDirectory()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                    extractedFiles.put(zipEntry.getName(), bos.toByteArray());
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
        return extractedFiles;
    }
}
