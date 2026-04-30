import java.io.*;

public class generate_test_log {
    public static void main(String[] args) {
        try (FileWriter fw = new FileWriter("large_test_log.txt");
             BufferedWriter bw = new BufferedWriter(fw)) {
            
            String[] logLevels = {"INFO", "DEBUG", "WARN", "ERROR"};
            String[] messages = {
                "Application started successfully",
                "Initializing database connection",
                "Database connection established",
                "Loading configuration from config.properties",
                "Configuration loaded successfully",
                "Memory usage is above 80%",
                "Performing garbage collection",
                "Memory usage is now normal",
                "User logged in from 192.168.1.100",
                "Processing request: GET /api/users",
                "Request processed successfully, returning 25 users",
                "Closing database connection",
                "Database connection closed",
                "Failed to load external module",
                "Using fallback implementation instead",
                "Fallback module loaded successfully",
                "Initializing cache system",
                "Cache system initialized with 1000 entries capacity",
                "Loading cached data from disk",
                "Cached data loaded successfully, 500 entries restored"
            };
            
            for (int i = 0; i < 500; i++) {
                String timestamp = String.format("2023-04-30 %02d:%02d:%02d", 
                    (int)(Math.random() * 24), 
                    (int)(Math.random() * 60), 
                    (int)(Math.random() * 60));
                String level = logLevels[(int)(Math.random() * logLevels.length)];
                String message = messages[(int)(Math.random() * messages.length)];
                
                bw.write(timestamp + " " + level + " - " + message + " [" + i + "]");
                bw.newLine();
            }
            
            System.out.println("Large test log file created: large_test_log.txt");
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
