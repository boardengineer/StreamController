import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Launcher {
    private static final String command = "CMD";

    public static void main(String[] args) {
        startClientGame();
    }

    private static void startClientGame() {
        try {

            String[] command = {System
                    .getProperty("java.home") + "/bin/java", "-jar", "C:\\stuff\\_ModTheSpire\\ModTheSpire.jar", "--profile", "Client", "--skip-launcher", "skip-intro"};
            // ProcessBuilder will execute process named 'CMD' and will provide '/C' and 'dir' as command line arguments to 'CMD'

            ProcessBuilder pbuilder = new ProcessBuilder(command);
            Process process = pbuilder.start();

            // Get input streams
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process
                    .getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process
                    .getErrorStream()));

            new Thread(() -> {
                String s= "";
                System.out.println("Standard error: ");
                while (true) {
                    try {
                        if (!((s = stdError.readLine()) != null))
                            break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    System.out.println(s);
                }
            }).start();

            // Read command standard output

            new Thread(() -> {
                String s = "";
                System.out.println("Standard output: ");
                while (true) {
                    try {
                        if (!((s = stdInput.readLine()) != null))
                            break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    System.out.println(s);
                }
            }).start();


        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}
