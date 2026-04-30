import java.io.*;
import java.util.*;
import java.util.zip.*;

public class LogSplitter {
    
    private static final String USAGE = "Usage: java LogSplitter <log_file_path> <split_size_MB> [compress(true/false)] [output_directory]";
    private static final int BUFFER_SIZE = 8192;
    private static final String DEFAULT_ENCODING = "UTF-8";
    
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
        
        String fileName = logFile.getName();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        
        try (FileInputStream fis = new FileInputStream(logFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long currentFileSize = 0;
            int fileIndex = 1;
            
            SplitFileWriter currentWriter = null;
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                if (currentWriter == null || currentFileSize + bytesRead > config.getSplitSizeBytes()) {
                    if (currentWriter != null) {
                        currentWriter.close();
                        File splitFile = currentWriter.getOutputFile();
                        
                        result.incrementSplitCount();
                        result.addToCompressedTotalSize(splitFile.length());
                        
                        System.out.println("Completed: " + splitFile.getName() + " (" + FileUtils.formatFileSize(splitFile.length()) + ")");
                    }
                    
                    currentFileSize = 0;
                    String splitFileName = baseName + "_" + fileIndex + extension;
                    File outputFile = getOutputFile(splitFileName);
                    
                    if (config.isCompress()) {
                        currentWriter = new ZipSplitFileWriter(outputFile, baseName + "_" + fileIndex + extension, conflictHandler);
                    } else {
                        currentWriter = new PlainSplitFileWriter(outputFile, conflictHandler);
                    }
                    
                    fileIndex++;
                }
                
                currentWriter.write(buffer, 0, bytesRead);
                currentFileSize += bytesRead;
            }
            
            if (currentWriter != null) {
                currentWriter.close();
                File splitFile = currentWriter.getOutputFile();
                
                result.incrementSplitCount();
                result.addToCompressedTotalSize(splitFile.length());
                
                System.out.println("Completed: " + splitFile.getName() + " (" + FileUtils.formatFileSize(splitFile.length()) + ")");
            }
        }
        
        result.calculateSavedPercentage();
        return result;
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

interface SplitFileWriter extends Closeable {
    void write(byte[] buffer, int offset, int length) throws IOException;
    File getOutputFile();
}

class PlainSplitFileWriter implements SplitFileWriter {
    private final File outputFile;
    private final BufferedOutputStream outputStream;
    private final FileConflictHandler conflictHandler;
    
    public PlainSplitFileWriter(File outputFile, FileConflictHandler conflictHandler) throws IOException {
        this.outputFile = outputFile;
        this.conflictHandler = conflictHandler;
        
        conflictHandler.checkConflict(outputFile);
        
        this.outputStream = new BufferedOutputStream(new FileOutputStream(outputFile));
    }
    
    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        outputStream.write(buffer, offset, length);
    }
    
    @Override
    public File getOutputFile() {
        return outputFile;
    }
    
    @Override
    public void close() throws IOException {
        outputStream.flush();
        outputStream.close();
    }
}

class ZipSplitFileWriter implements SplitFileWriter {
    private final File output