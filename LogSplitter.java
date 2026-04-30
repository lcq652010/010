import java.io.*;
import java.util.*;
import java.util.zip.*;

public class LogSplitter {
    
    private static final String USAGE = "Usage: java LogSplitter <log_file_path> <split_size_MB> [compress(true/false)] [output_directory]";
    
    public static void main(String[] args) {
        try {
            CommandLineParser parser = new CommandLineParser();
            SplitterConfig config = parser.parse(args);
            
            System.out.println("=== Log File Splitter Tool ===");
            System.out.println("Original file: " + config.getLogFile().getAbsolutePath());
            System.out.println("Original file size: " + FileUtils.formatFileSize(config.getLogFile().length()));
            System.out.println("Split size: " + config.getSplitSizeMB() + " MB");
            System.out.println("Compress: " + (config.isCompress() ? "Yes" : "No"));
            if (config.getOutputDir() != null) {
                System.out.println("Output directory: " + config.getOutputDir().getAbsolutePath());
            }
            System.out.println("========================================");
            
            LogFileSplitter splitter = new LogFileSplitter(config);
            SplitResult result = splitter.split();
            
            System.out.println("\n=== Split Result Statistics ===");
            System.out.println("Original file size: " + FileUtils.formatFileSize(result.getOriginalFileSize()));
            System.out.println("Number of splits: " + result.getSplitCount());
            System.out.println("Total compressed size: " + FileUtils.formatFileSize(result.getCompressedTotalSize()));
            System.out.println("Space saved: " + FileUtils.formatFileSize(result.getOriginalFileSize() - result.getCompressedTotalSize()));
            System.out.println("Space saved percentage: " + String.format("%.2f%%", result.getSavedPercentage()));
            
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
            System.out.println(USAGE);
            System.exit(1);
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

class CommandLineParser {
    
    public SplitterConfig parse(String[] args) {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("Invalid number of arguments");
        }
        
        String logFilePath = args[0];
        double splitSizeMB = parseSplitSize(args[1]);
        boolean compress = args.length >= 3 ? parseCompress(args[2]) : true;
        String outputDirPath = args.length >= 4 ? args[3] : null;
        
        File logFile = new File(logFilePath);
        if (!logFile.exists() || !logFile.isFile()) {
            throw new IllegalArgumentException("Log file does not exist or is not a file: " + logFilePath);
        }
        
        if (splitSizeMB <= 0) {
            throw new IllegalArgumentException("Split size must be greater than 0");
        }
        
        return new SplitterConfig(logFile, splitSizeMB, compress, outputDirPath);
    }
    
    private double parseSplitSize(String sizeStr) {
        try {
            return Double.parseDouble(sizeStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Split size must be a valid number");
        }
    }
    
    private boolean parseCompress(String compressStr) {
        return Boolean.parseBoolean(compressStr);
    }
}

class SplitterConfig {
    private final File logFile;
    private final double splitSizeMB;
    private final long splitSizeBytes;
    private final boolean compress;
    private final File outputDir;
    
    public SplitterConfig(File logFile, double splitSizeMB, boolean compress, String outputDirPath) {
        this.logFile = logFile;
        this.splitSizeMB = splitSizeMB;
        this.splitSizeBytes = (long) (splitSizeMB * 1024 * 1024);
        this.compress = compress;
        this.outputDir = outputDirPath != null ? new File(outputDirPath) : null;
    }
    
    public File getLogFile() { return logFile; }
    public double getSplitSizeMB() { return splitSizeMB; }
    public long getSplitSizeBytes() { return splitSizeBytes; }
    public boolean isCompress() { return compress; }
    public File getOutputDir() { return outputDir; }
}

class LogFileSplitter {
    private static final int BUFFER_SIZE = 8192;
    
    private final SplitterConfig config;
    private final SplitResult result;
    private final FileConflictHandler conflictHandler;
    
    public LogFileSplitter(SplitterConfig config) {
        this.config = config;
        this.result = new SplitResult();
        this.conflictHandler = new FileConflictHandler();
    }
    
    public SplitResult split() throws IOException {
        prepareOutputDirectory();
        
        File logFile = config.getLogFile();
        result.setOriginalFileSize(logFile.length());
        
        if (logFile.length() == 0) {
            System.out.println("Warning: Log file is empty, no split files will be created");
            return result;
        }
        
        String fileName = logFile.getName();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        
        SplitFileWriter currentWriter = null;
        
        try (FileInputStream fis = new FileInputStream(logFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long currentFileSize = 0;
            int fileIndex = 1;
            
            try {
                while ((bytesRead = bis.read(buffer)) != -1) {
                    int offset = 0;
                    int remaining = bytesRead;
                    
                    while (remaining > 0) {
                        long spaceLeft = config.getSplitSizeBytes() - currentFileSize;
                        
                        if (spaceLeft <= 0 || currentWriter == null) {
                            if (currentWriter != null) {
                                closeWriterAndRecordStats(currentWriter);
                            }
                            
                            currentFileSize = 0;
                            String splitFileName = baseName + "_" + fileIndex + extension;
                            File outputFile = getOutputFile(splitFileName);
                            
                            if (config.isCompress()) {
                                currentWriter = SplitFileWriterFactory.createZipWriter(outputFile, baseName + "_" + fileIndex + extension, conflictHandler);
                            } else {
                                currentWriter = SplitFileWriterFactory.createPlainWriter(outputFile, conflictHandler);
                            }
                            
                            fileIndex++;
                            spaceLeft = config.getSplitSizeBytes();
                        }
                        
                        int bytesToWrite = (int) Math.min(remaining, spaceLeft);
                        currentWriter.write(buffer, offset, bytesToWrite);
                        currentFileSize += bytesToWrite;
                        offset += bytesToWrite;
                        remaining -= bytesToWrite;
                    }
                }
            } finally {
                if (currentWriter != null) {
                    closeWriterAndRecordStats(currentWriter);
                    currentWriter = null;
                }
            }
        }
        
        result.calculateSavedPercentage();
        return result;
    }
    
    private void closeWriterAndRecordStats(SplitFileWriter writer) throws IOException {
        File splitFile = writer.getOutputFile();
        long fileSizeBeforeClose = splitFile.length();
        
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("Warning: Error closing writer: " + e.getMessage());
            throw e;
        }
        
        long fileSize = splitFile.length();
        if (fileSize == 0) {
            if (splitFile.delete()) {
                System.out.println("Deleted empty file: " + splitFile.getName());
            }
            return;
        }
        
        result.incrementSplitCount();
        result.addToCompressedTotalSize(fileSize);
        
        System.out.println("Completed: " + splitFile.getName() + " (" + FileUtils.formatFileSize(fileSize) + ")");
    }
    
    private void prepareOutputDirectory() throws IOException {
        if (config.getOutputDir() != null) {
            File outputDir = config.getOutputDir();
            if (!outputDir.exists()) {
                if (outputDir.mkdirs()) {
                    System.out.println("Created output directory: " + outputDir.getAbsolutePath());
                } else {
                    throw new IOException("Failed to create output directory: " + outputDir.getAbsolutePath());
                }
            } else if (!outputDir.isDirectory()) {
                throw new IOException("Output path is not a directory: " + outputDir.getAbsolutePath());
            }
        }
    }
    
    private File getOutputFile(String fileName) {
        if (config.getOutputDir() != null) {
            return new File(config.getOutputDir(), fileName + (config.isCompress() ? ".zip" : ""));
        } else {
            String parentDir = config.getLogFile().getParent();
            if (parentDir != null && !parentDir.isEmpty()) {
                return new File(parentDir, fileName + (config.isCompress() ? ".zip" : ""));
            } else {
                return new File(fileName + (config.isCompress() ? ".zip" : ""));
            }
        }
    }
}

class SplitFileWriterFactory {
    
    public static SplitFileWriter createPlainWriter(File outputFile, FileConflictHandler conflictHandler) throws IOException {
        conflictHandler.checkConflict(outputFile);
        
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        
        try {
            fos = new FileOutputStream(outputFile);
            bos = new BufferedOutputStream(fos);
            return new PlainSplitFileWriter(outputFile, bos);
        } catch (IOException e) {
            closeQuietly(bos);
            closeQuietly(fos);
            throw e;
        }
    }
    
    public static SplitFileWriter createZipWriter(File outputFile, String entryName, FileConflictHandler conflictHandler) throws IOException {
        conflictHandler.checkConflict(outputFile);
        
        FileOutputStream fos = null;
        BufferedOutputStream bos = null;
        ZipOutputStream zos = null;
        
        try {
            fos = new FileOutputStream(outputFile);
            bos = new BufferedOutputStream(fos);
            zos = new ZipOutputStream(bos);
            
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            
            return new ZipSplitFileWriter(outputFile, zos);
        } catch (IOException e) {
            closeQuietly(zos);
            closeQuietly(bos);
            closeQuietly(fos);
            throw e;
        }
    }
    
    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}

interface SplitFileWriter extends Closeable {
    void write(byte[] buffer, int offset, int length) throws IOException;
    File getOutputFile();
}

class PlainSplitFileWriter implements SplitFileWriter {
    private final File outputFile;
    private final BufferedOutputStream outputStream;
    private boolean closed = false;
    
    public PlainSplitFileWriter(File outputFile, BufferedOutputStream outputStream) {
        this.outputFile = outputFile;
        this.outputStream = outputStream;
    }
    
    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (closed) {
            throw new IOException("Writer is already closed");
        }
        outputStream.write(buffer, offset, length);
    }
    
    @Override
    public File getOutputFile() {
        return outputFile;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                outputStream.flush();
            } finally {
                outputStream.close();
            }
        }
    }
}

class ZipSplitFileWriter implements SplitFileWriter {
    private final File outputFile;
    private final ZipOutputStream zipOutputStream;
    private boolean closed = false;
    private boolean entryClosed = false;
    
    public ZipSplitFileWriter(File outputFile, ZipOutputStream zipOutputStream) {
        this.outputFile = outputFile;
        this.zipOutputStream = zipOutputStream;
    }
    
    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (closed) {
            throw new IOException("Writer is already closed");
        }
        zipOutputStream.write(buffer, offset, length);
    }
    
    @Override
    public File getOutputFile() {
        return outputFile;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                if (!entryClosed) {
                    zipOutputStream.closeEntry();
                    entryClosed = true;
                }
                zipOutputStream.flush();
            } finally {
                zipOutputStream.close();
            }
        }
    }
}

class FileConflictHandler {
    
    public void checkConflict(File file) throws IOException {
        if (file.exists()) {
            System.out.println("Warning: File already exists and will be overwritten: " + file.getAbsolutePath());
        }
    }
}

class SplitResult {
    private long originalFileSize;
    private int splitCount;
    private long compressedTotalSize;
    private double savedPercentage;
    
    public SplitResult() {
        this.originalFileSize = 0;
        this.splitCount = 0;
        this.compressedTotalSize = 0;
        this.savedPercentage = 0.0;
    }
    
    public void setOriginalFileSize(long originalFileSize) {
        this.originalFileSize = originalFileSize;
    }
    
    public void incrementSplitCount() {
        this.splitCount++;
    }
    
    public void addToCompressedTotalSize(long size) {
        this.compressedTotalSize += size;
    }
    
    public void calculateSavedPercentage() {
        if (originalFileSize > 0) {
            long savedSize = originalFileSize - compressedTotalSize;
            this.savedPercentage = (savedSize * 100.0) / originalFileSize;
        }
    }
    
    public long getOriginalFileSize() { return originalFileSize; }
    public int getSplitCount() { return splitCount; }
    public long getCompressedTotalSize() { return compressedTotalSize; }
    public double getSavedPercentage() { return savedPercentage; }
}

class FileUtils {
    
    public static String formatFileSize(long sizeBytes) {
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
}
