import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class LogSplitter {
    
    private static final String USAGE = "Usage: java LogSplitter <log_file_path> <split_size_MB> [compress(true/false)]";
    private static final int BUFFER_SIZE = 8192;
    
    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println(USAGE);
            System.exit(1);
        }
        
        String logFilePath = args[0];
        double splitSizeMB = parseSplitSize(args[1]);
        boolean compress = args.length >= 3 ? parseCompress(args[2]) : true;
        
        File logFile = new File(logFilePath);
        if (!logFile.exists() || !logFile.isFile()) {
            System.out.println("Error: Log file does not exist or is not a file: " + logFilePath);
            System.exit(1);
        }
        
        if (splitSizeMB <= 0) {
            System.out.println("Error: Split size must be greater than 0");
            System.exit(1);
        }
        
        long splitSizeBytes = (long) (splitSizeMB * 1024 * 1024);
        long originalFileSize = logFile.length();
        
        System.out.println("=== Log File Splitter Tool ===");
        System.out.println("Original file: " + logFile.getAbsolutePath());
        System.out.println("Original file size: " + formatFileSize(originalFileSize));
        System.out.println("Split size: " + splitSizeMB + " MB");
        System.out.println("Compress: " + (compress ? "Yes" : "No"));
        System.out.println("========================================");
        
        try {
            SplitResult result = splitAndCompressLogFile(logFile, splitSizeBytes, compress);
            
            System.out.println("\n=== Split Result Statistics ===");
            System.out.println("Original file size: " + formatFileSize(result.originalFileSize));
            System.out.println("Number of splits: " + result.splitCount);
            System.out.println("Total compressed size: " + formatFileSize(result.compressedTotalSize));
            System.out.println("Space saved: " + formatFileSize(result.originalFileSize - result.compressedTotalSize));
            System.out.println("Space saved percentage: " + String.format("%.2f%%", result.savedPercentage));
            
        } catch (IOException e) {
            System.out.println("Error: Exception during split process: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static double parseSplitSize(String sizeStr) {
        try {
            return Double.parseDouble(sizeStr);
        } catch (NumberFormatException e) {
            System.out.println("Error: Split size must be a number");
            System.exit(1);
            return 0;
        }
    }
    
    private static boolean parseCompress(String compressStr) {
        return Boolean.parseBoolean(compressStr);
    }
    
    private static SplitResult splitAndCompressLogFile(File logFile, long splitSizeBytes, boolean compress) throws IOException {
        SplitResult result = new SplitResult();
        result.originalFileSize = logFile.length();
        result.splitCount = 0;
        result.compressedTotalSize = 0;
        
        String parentDir = logFile.getParent();
        String fileName = logFile.getName();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        
        try (FileInputStream fis = new FileInputStream(logFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long currentFileSize = 0;
            int fileIndex = 1;
            
            OutputStream currentOutputStream = null;
            ZipOutputStream zipOutputStream = null;
            File currentSplitFile = null;
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                if (currentOutputStream == null || currentFileSize + bytesRead > splitSizeBytes) {
                    if (currentOutputStream != null) {
                        currentOutputStream.close();
                        if (compress && zipOutputStream != null) {
                            zipOutputStream.close();
                        }
                        
                        if (compress) {
                            result.compressedTotalSize += currentSplitFile.length();
                        } else {
                            result.compressedTotalSize += currentSplitFile.length();
                        }
                        
                        System.out.println("Completed: " + currentSplitFile.getName() + " (" + formatFileSize(currentSplitFile.length()) + ")");
                    }
                    
                    currentFileSize = 0;
                    String splitFileName = parentDir != null && !parentDir.isEmpty() 
                            ? parentDir + File.separator + baseName + "_" + fileIndex + extension
                            : baseName + "_" + fileIndex + extension;
                    
                    if (compress) {
                        splitFileName += ".zip";
                        currentSplitFile = new File(splitFileName);
                        FileOutputStream fos = new FileOutputStream(currentSplitFile);
                        zipOutputStream = new ZipOutputStream(new BufferedOutputStream(fos));
                        ZipEntry entry = new ZipEntry(baseName + "_" + fileIndex + extension);
                        zipOutputStream.putNextEntry(entry);
                        currentOutputStream = zipOutputStream;
                    } else {
                        currentSplitFile = new File(splitFileName);
                        currentOutputStream = new BufferedOutputStream(new FileOutputStream(currentSplitFile));
                    }
                    
                    fileIndex++;
                    result.splitCount++;
                }
                
                currentOutputStream.write(buffer, 0, bytesRead);
                currentFileSize += bytesRead;
            }
            
            if (currentOutputStream != null) {
                currentOutputStream.close();
                if (compress && zipOutputStream != null) {
                    zipOutputStream.close();
                }
                
                if (compress) {
                    result.compressedTotalSize += currentSplitFile.length();
                } else {
                    result.compressedTotalSize += currentSplitFile.length();
                }
                
                System.out.println("Completed: " + currentSplitFile.getName() + " (" + formatFileSize(currentSplitFile.length()) + ")");
            }
        }
        
        if (result.originalFileSize > 0) {
            long savedSize = result.originalFileSize - result.compressedTotalSize;
            result.savedPercentage = (savedSize * 100.0) / result.originalFileSize;
        }
        
        return result;
    }
    
    private static String formatFileSize(long sizeBytes) {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        } else if (sizeBytes < 1024 * 1024) {
            return String.format("%.2f KB", sizeBytes / 1024.0);
        } else if (sizeBytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    private static class SplitResult {
        long originalFileSize;
        int splitCount;
        long compressedTotalSize;
        double savedPercentage;
    }
}
