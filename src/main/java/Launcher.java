import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class Launcher {
    private static final String command = "CMD";
    private static final int SERVER_PORT = 5123;

    private static DataInputStream in;
    private static DataOutputStream out;

    private static JLabel la1;

    public static void main(String[] args) {
        JFrame f = new JFrame("Stream Control Panel");
        JLabel la2;
        la1 = new JLabel("Java Programming");
        la1.setBounds(70, 70, 120, 50);
        la2 = new JLabel("Java Swing");
        la2.setBounds(70, 120, 120, 50);
        f.add(la1);
        f.add(la2);

        JButton startGameButton = new JButton("Start Game");
        startGameButton.setBounds(70, 120, 115, 50);
        f.add(startGameButton);

        JButton sendKillButton = new JButton("Send Kill");
        sendKillButton.setBounds(70, 175, 115, 50);
        f.add(sendKillButton);

        JButton sendStartButton = new JButton("Start Run");
        sendStartButton.setBounds(70, 250, 115, 50);
        f.add(sendStartButton);

        sendKillButton.addActionListener(e -> sendKill());
        sendStartButton.addActionListener(e -> sendStart());

        startGameButton.addActionListener(e -> {
            startClientGame();
            waitForControllerSignal();
        });

        f.setSize(700, 700);
        f.setLayout(null);
        f.setVisible(true);

//        JavaProgressBar m = new JavaProgressBar();
//        m.setVisible(true);
//        m.iterate();

    }

    private static void startClientGame() {
        try {
            String[] command = {System
                    .getProperty("java.home") + "/bin/java", "-jar", "-DisClient=true", "C:\\stuff\\_ModTheSpire\\ModTheSpire.jar", "--profile", "Client", "--skip-launcher", "--skip-intro"};
            // ProcessBuilder will execute process named 'CMD' and will provide '/C' and 'dir' as command line arguments to 'CMD'

            ProcessBuilder pbuilder = new ProcessBuilder(command);
            Process process = pbuilder.start();

            // Get input streams
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process
                    .getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process
                    .getErrorStream()));

            new Thread(() -> {
                String s = "";
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

    private static void waitForControllerSignal() {
        System.out.println("Waiting for game to start...");
        new Thread(() -> {
            try {
                System.out.println("Waiting for game to start...");
                ServerSocket serverSocket = new ServerSocket(SERVER_PORT);

                Socket socket = serverSocket.accept();

                in = new DataInputStream(new BufferedInputStream(socket
                        .getInputStream()));
                out = new DataOutputStream(socket.getOutputStream());

                System.out.println("Waiting for game to start...");

                String clientResponse = in.readUTF();

                System.out.println("client wrote " + clientResponse);
                if (clientResponse.equals("SUCCESS")) {
                    la1.setForeground(Color.green);
                }
            } catch (IOException e) {

            }
        }).start();
    }

    private static void sendStart() {
        new Thread(() -> {
            try {
                out.writeUTF("start");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void sendKill() {
        new Thread(() -> {
            try {
                out.writeUTF("kill");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
