package com.whisperonnx;

import java.io.File;
import java.io.IOException;

public class ZipSlipSecurityTest {

    public static void main(String[] args) throws IOException {
        testSafePath();
        testMaliciousPath();
        testTrickySafePath();
        System.out.println("All tests passed!");
    }

    private static void testSafePath() throws IOException {
        File targetDir = new File(".").getCanonicalFile();
        String zipEntryName = "safe.txt";
        File destination = new File(targetDir, zipEntryName);

        validatePath(targetDir, destination);
        System.out.println("Safe path test passed.");
    }

    private static void testTrickySafePath() throws IOException {
        File targetDir = new File(".").getCanonicalFile();
        // This resolves back to the target dir, so it should be safe
        String zipEntryName = "../" + targetDir.getName() + "/safe.txt";
        File destination = new File(targetDir, zipEntryName);

        validatePath(targetDir, destination);
        System.out.println("Tricky safe path test passed.");
    }

    private static void testMaliciousPath() throws IOException {
        File targetDir = new File(".").getCanonicalFile();
        // Construct a path that attempts to escape the directory
        String zipEntryName = "../evil.txt";
        File destination = new File(targetDir, zipEntryName);

        try {
            validatePath(targetDir, destination);
            throw new RuntimeException("Test failed: Malicious path was accepted!");
        } catch (SecurityException e) {
            System.out.println("Malicious path test passed: " + e.getMessage());
        }
    }

    // This is the logic we will inject into SetupActivity.java
    private static void validatePath(File targetDir, File extractedFile) throws IOException {
        String canonicalPath = extractedFile.getCanonicalPath();
        String canonicalTarget = targetDir.getCanonicalPath();

        // Ensure target ends with separator to prevent partial name matching
        if (!canonicalTarget.endsWith(File.separator)) {
            canonicalTarget += File.separator;
        }

        if (!canonicalPath.startsWith(canonicalTarget)) {
            throw new SecurityException("Zip Slip vulnerability detected! Path: " + canonicalPath);
        }
    }
}
