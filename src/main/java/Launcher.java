import basemod.ReflectionHacks;
import com.gikk.twirk.Twirk;
import com.gikk.twirk.TwirkBuilder;
import com.gikk.twirk.events.TwirkListener;
import com.gikk.twirk.types.twitchMessage.TwitchMessage;
import com.gikk.twirk.types.users.TwitchUser;
import de.robojumper.ststwitch.TwitchConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;

public class Launcher {
    private static final String HOST_IP = "127.0.0.1";
    private static boolean isModded = false;

    private static final String CLIENT_PROFILE = "Client";
    private static final String SERVER_PROFILE = "Server";
    private static final String CLIENT_MODDED_PROFILE = "Client_modded";
    private static final String SERVER_MODDED_PROFILE = "Server_modded";

    private static final int CLIENT_GAME_PORT = 5123;
    private static final int SERVER_GAME_PORT = 5124;

    private static final long FIVE_MINUTES = 1_000 * 60 * 5;

    private static DataInputStream gameInputStream;
    private static DataOutputStream gameOutputStream;

    private static DataInputStream serverInputStream;
    private static DataOutputStream serverOutputStream;

    static Process clientGameProcess;
    static Process serverGameProcess;

    static Socket clientGameSocket;
    static Socket serverGameSocket;

    static ServerSocket clientGameServerSocket;
    static Socket serverGameServerSocket;

    // If set to true, the game process will be killed and restarted
    static boolean shouldKillClientGame = false;
    static boolean shouldKillServerGame = false;


    private static final boolean shouldSendEnable = true;

    private static boolean isClientActive = false;
    private static boolean isServerActive = false;

    private static boolean startedStartThread = false;

    private static TwitchConfig twitchConfig;

    private static Twirk twirk;

    public static void main(String[] args) {
        Optional<TwitchConfig> twitchConfigOptional = TwitchConfig.readConfig();

        if (twitchConfigOptional.isPresent()) {
            twitchConfig = twitchConfigOptional.get();

            String channel = ReflectionHacks
                    .getPrivate(twitchConfig, TwitchConfig.class, "channel");
            String username = ReflectionHacks
                    .getPrivate(twitchConfig, TwitchConfig.class, "username");
            String token = ReflectionHacks.getPrivate(twitchConfig, TwitchConfig.class, "token");

            System.out.println(channel);

            try {
                twirk = new TwirkBuilder(channel, username, token).setSSL(true).build();

                twirk.addIrcListener(new TwirkListener() {
                    @Override
                    public void onPrivMsg(TwitchUser sender, TwitchMessage message) {
                        receiveMessage(sender, message.getContent());
                    }
                });

                twirk.connect();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }

        trackTwirkConnection();
    }

    private static void receiveMessage(TwitchUser sender, String content) {
        boolean shouldPayAttention = sender.isMod() || sender.isOwner();

        if (shouldPayAttention) {
            String[] contentTokens = content.split(" ");

            if (contentTokens.length >= 2) {
                if (contentTokens[0].equals("!admin")) {
                    String command = contentTokens[1];
                    if (command.equals("startgame")) {
                        sendMessage("Starting Game");
                        if (clientGameProcess == null || !clientGameProcess.isAlive()) {
                            startRunningGame();
                        }
                    } else if (command.equals("restartserver")) {
                        sendMessage("Request Server Restart");
                        shouldKillServerGame = true;
                    } else if (command.equals("winbattle")) {
                        sendMessage("Sending Kill All");
                        sendKill();
                    } else if (command.equals("restartall")) {
                        sendMessage("Requesting Restart All");
                        shouldKillClientGame = true;
                        shouldKillServerGame = true;

                        startGameAfterStart();
                    } else if (command.equals("state")) {
                        sendMessage("Requesting State Update");
                        requestState();
                    } else if (command.equals("advancegame")) {
                        sendMessage("Requesting Game Advance");
                        requestAdvanceBattle();
                    } else if (command.equals("losebattle")) {
                        sendMessage("Requesting Battle Loss");
                        requestBattleLoss();
                    } else if (command.equals("enablemods")) {
                        sendMessage("enabling mods (on restart)");
                        isModded = true;
                    } else if (command.equals("disablemods")) {
                        sendMessage("disabling mods (on restart)");
                        isModded = false;
                    }
                }
            }
        }
    }

    private static Process startClientGame() {
        try {
            String clientProfileName = isModded ? CLIENT_MODDED_PROFILE : CLIENT_PROFILE;
            String[] command = {System
                    .getProperty("java.home") + "/bin/java", "-jar", "-DisClient=true", "C:\\stuff\\_ModTheSpire\\ModTheSpire.jar", "--profile", clientProfileName, "--skip-launcher", "--skip-intro"};
            // ProcessBuilder will execute process named 'CMD' and will provide '/C' and 'dir' as command line arguments to 'CMD'

            ProcessBuilder pbuilder = new ProcessBuilder(command);
            Process process = pbuilder.start();

            // Get input streams
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process
                    .getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process
                    .getErrorStream()));

            PrintWriter standardOut = new PrintWriter(new File(String
                    .format("logs/client/stdout-%s.txt", System.currentTimeMillis())));
            PrintWriter standardErr = new PrintWriter(new File(String
                    .format("logs/client/stderr-%s.txt", System.currentTimeMillis())));

            new Thread(() -> {
                String s = "";
                while (true) {
                    try {
                        if (!((s = stdError.readLine()) != null))
                            break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    standardErr.println(s);
                    standardErr.flush();
                }
            }).start();

            // Read command standard output

            new Thread(() -> {
                String s = "";
                while (true) {
                    try {
                        if (!((s = stdInput.readLine()) != null))
                            break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    standardOut.println(s);
                    standardOut.flush();
                }
            }).start();

            sendMessage("Client Launched, Waiting for Startup Signal...");
            waitForClientSuccessSignal();
            return process;
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        return null;
    }

    private static Process startServerGame() {
        try {
            String serverProfilename = isModded ? SERVER_MODDED_PROFILE : SERVER_PROFILE;
            String[] command = {System
                    .getProperty("java.home") + "/bin/java", "-Xms1024m", "-Xmx2048m", "-jar", "-DisServer=true", "C:\\stuff\\_ModTheSpire\\ModTheSpire.jar", "--profile", serverProfilename, "--skip-launcher", "--skip-intro"};
            // ProcessBuilder will execute process named 'CMD' and will provide '/C' and 'dir' as command line arguments to 'CMD'

            ProcessBuilder pbuilder = new ProcessBuilder(command);

            System.out.println("Starting server game");
            Process process = pbuilder.start();

            // Get input streams
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process
                    .getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process
                    .getErrorStream()));

            PrintWriter standardOut = new PrintWriter(new File(String
                    .format("logs/server/stdout-%s.txt", System.currentTimeMillis())));
            PrintWriter standardErr = new PrintWriter(new File(String
                    .format("logs/server/stderr-%s.txt", System.currentTimeMillis())));

            new Thread(() -> {
                String s = "";
                while (true) {
                    try {
                        if (!((s = stdError.readLine()) != null))
                            break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    standardErr.println(s);
                    standardErr.flush();
                }
            }).start();

            // Read command standard output

            new Thread(() -> {
                String s = "";
                while (true) {
                    try {
                        if (!((s = stdInput.readLine()) != null))
                            break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    standardOut.println(s);
                    standardOut.flush();
                }
            }).start();

            sendMessage("Server Launched, Waiting for Startup Signal...");
            waitForServerSuccessSignal();
            return process;
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        return null;
    }

    private static void waitForClientSuccessSignal() {
        new Thread(() -> {
            try {
                if (clientGameServerSocket != null) {
                    clientGameServerSocket.close();
                }

                if (clientGameSocket != null) {
                    clientGameSocket.close();
                }

                clientGameServerSocket = new ServerSocket(CLIENT_GAME_PORT);

                clientGameSocket = clientGameServerSocket.accept();

                gameInputStream = new DataInputStream(new BufferedInputStream(clientGameSocket
                        .getInputStream()));
                gameOutputStream = new DataOutputStream(clientGameSocket.getOutputStream());

                gameInputStream.readUTF();

                sendMessage("Client Startup Message Received");
                isClientActive = true;
            } catch (IOException e) {

            }
        }).start();
    }

    private static void waitForServerSuccessSignal() {
        new Thread(() -> {
            try {
                if (serverGameServerSocket != null) {
                    serverGameServerSocket.close();
                }

                if (serverGameSocket != null) {
                    serverGameSocket.close();
                }


                System.out.println("Waiting for server to start... ");

                boolean connected = false;

                long waitTime = 0;
                long startTime = System.currentTimeMillis();

                while (!connected && waitTime < FIVE_MINUTES) {
                    try {
                        waitTime = System.currentTimeMillis() - startTime;

                        System.out
                                .printf("Waiting for Server to Start %d / %d\n", waitTime, FIVE_MINUTES);
                        serverGameServerSocket = new Socket();
                        serverGameServerSocket
                                .connect(new InetSocketAddress(HOST_IP, SERVER_GAME_PORT));
                        connected = true;
                        System.out.println("Server Connected");

                        Thread.sleep(3_000);
                    } catch (IOException | InterruptedException e) {
//                    e.printStackTrace();
                    }
                }

                serverInputStream = new DataInputStream(new BufferedInputStream(serverGameServerSocket
                        .getInputStream()));
                serverOutputStream = new DataOutputStream(serverGameServerSocket.getOutputStream());

                serverOutputStream.writeUTF("ping");
                String serverResponse = serverInputStream.readUTF();
                System.out.println("server wrote " + serverResponse);
                sendMessage("Server Startup Message Received");
                isServerActive = true;
                if (isClientActive) {
                    requestBattleRestart();
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void requestState() {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("state");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void sendEnable() {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("enable");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void sendKill() {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("kill");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void requestBattleRestart() {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("battlerestart");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void requestAdvanceBattle() {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("advancegame");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void requestBattleLoss() {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("losebattle");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void trackClientProcess() {
        new Thread(() -> {
            try {
                while (true) {
                    if (clientGameProcess.isAlive()) {
                        if (shouldKillClientGame) {
                            isClientActive = false;
                            shouldKillClientGame = false;
                            clientGameProcess.destroy();
                        }
                        Thread.sleep(3_000);
                    } else {
                        clientGameProcess = startClientGame();
                        trackClientProcess();

                        startGameAfterStart();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void trackTwirkConnection() {
        new Thread(() -> {
            try {
                while (true) {
                    if (twirk.isConnected()) {
                        Thread.sleep(3_000);
                    } else {
                        try {
                            System.err.println("attempting to reconnect");
                            try {
                                twirk.close();
                            } catch (NullPointerException e) {
                            }

                            String channel = ReflectionHacks
                                    .getPrivate(twitchConfig, TwitchConfig.class, "channel");
                            String username = ReflectionHacks
                                    .getPrivate(twitchConfig, TwitchConfig.class, "username");
                            String token = ReflectionHacks
                                    .getPrivate(twitchConfig, TwitchConfig.class, "token");

                            twirk = new TwirkBuilder(channel, username, token).setSSL(true).build();

                            twirk.addIrcListener(new TwirkListener() {
                                @Override
                                public void onPrivMsg(TwitchUser sender, TwitchMessage message) {
                                    receiveMessage(sender, message.getContent());
                                }
                            });

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }).start();
    }

    private static void trackServerProcess() {
        new Thread(() -> {
            try {
                while (true) {
                    if (serverGameProcess.isAlive()) {
                        if (shouldKillServerGame) {
                            isServerActive = false;
                            shouldKillServerGame = false;
                            serverGameProcess.destroy();
                        }
                    } else {
                        if (!isClientActive) {
                            // Give the client some time to get ahead and snag the screen
                            Thread.sleep(3_000);
                        }

                        System.out.println("Server process not alive, restarting...");
                        serverGameProcess = startServerGame();
                        trackServerProcess();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void startGameAfterStart() {
        startedStartThread = false;
        new Thread(() -> {
            try {
                while (!startedStartThread) {
                    if (isClientActive && isServerActive && !shouldKillServerGame && !shouldKillClientGame) {
                        startedStartThread = true;
                        sendMessage("Running Game Found Sending Enable in 10 seconds...");
                        Thread.sleep(10_000);
                        sendEnable();
                        sendMessage("Enable Sent, Requesting State update in 2 seconds...");
                        Thread.sleep(2_000);
                        requestState();
                        sendMessage("Game Started");
                        return;
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void startRunningGame() {
        try {
            clientGameProcess = startClientGame();
            serverGameProcess = startServerGame();

            System.out.println("server game launched, waiting...");

            Thread.sleep(2_000);

            trackClientProcess();
            trackServerProcess();

            startGameAfterStart();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private static void sendMessage(String message) {
        if (twirk != null && twirk.isConnected()) {
            twirk.channelMessage("[Admin BOT] " + message);
        }
    }
}