import basemod.ReflectionHacks;
import com.gikk.twirk.Twirk;
import com.gikk.twirk.TwirkBuilder;
import com.gikk.twirk.events.TwirkListener;
import com.gikk.twirk.types.twitchMessage.TwitchMessage;
import com.gikk.twirk.types.users.TwitchUser;
import com.google.gson.JsonObject;
import de.robojumper.ststwitch.TwitchConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
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
                    } else if (command.equals("loserelic")) {
                        if (contentTokens.length >= 3) {
                            sendMessage("Requesting relic loss");
                            requestLoseRelic(contentTokens[2]);
                        }
                    } else if (command.equals("addkeys")) {
                        sendMessage("Requesting All Keys");
                        requestAllKeys();
                    } else if (command.equals("addrelic")) {
                        if (contentTokens.length >= 3) {
                            sendMessage("Requesting relic add");
                            requestAddRelic(contentTokens[2]);
                        }
                    } else if (command.equals("loadlegacy")) {
                        if (contentTokens.length >= 4) {
                            //sendLoadRequest();
                            sendMessage("Attempting Load Request");
                            try {
                                sendLoadRequest("C:/stuff/rundata/runs", contentTokens[2],
                                        Integer.parseInt(contentTokens[3]));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    } else if (command.equals("load")) {
                        if (contentTokens.length >= 3) {
                            sendMessage("Attempting Load Request");
                            try {
                                sendLoadRequest("C:/stuff/_ModTheSpire/startstates", contentTokens[2],
                                        Integer.parseInt(contentTokens[3]));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
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
                    System.err.println(s);
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
                    System.out.println(s);
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
                        System.out.println("Server Connected");

                        serverInputStream = new DataInputStream(new BufferedInputStream(serverGameServerSocket
                                .getInputStream()));
                        serverOutputStream = new DataOutputStream(serverGameServerSocket
                                .getOutputStream());

                        serverOutputStream.writeUTF("ping");
                        String serverResponse = serverInputStream.readUTF();
                        System.out.println("server wrote " + serverResponse);
                        sendMessage("Server Startup Message Received");
                        isServerActive = true;
                        if (isClientActive) {
                            requestBattleRestart();
                        }
                        connected = true;

                        Thread.sleep(3_000);
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
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


    private static void requestAllKeys() {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("addkeys");
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

    private static void requestLoseRelic(String relicName) {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("loserelic " + relicName);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void requestAddRelic(String relicName) {
        new Thread(() -> {
            try {
                gameOutputStream.writeUTF("addrelic " + relicName);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void sendLoadRequest(String root, String seed, int floorNum) {
        new Thread(() -> {
            try {
                // C:/stuff/_ModTheSpire/startstates
                // C:/stuff/rundata/runs
                String floorDir = String.format(root + "/%s/%02d", seed, floorNum);

                final String[] paths = new String[2];
                Files.list(new File(floorDir).toPath()).forEach(path -> {
                    if (path.toString().endsWith("autosave")) {
                        // This is the save file
                        paths[0] = path
                                .toString(); // i.e. C:/stuff/rundata/runs/%s/%02d/IRONCLAD.autosave
                        paths[1] = path.getFileName().toString(); // i.e. IRONCLAD.autosave
                    }
                });

                if (paths[0] != null && paths[1] != null) {
                    String playerClass = paths[1].split("\\.")[0];
                    sendLoadRequest(paths[0], playerClass, 1, 0);
                }

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

    static void sendLoadRequest(String path, String playerClass, int start, int end) {
        try {
            System.err.println("should request loading " + start + " to " + end);
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(HOST_IP, 5200));

            try {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                JsonObject requestJson = new JsonObject();

                requestJson.addProperty("command", "load");

                requestJson.addProperty("replay_floor_start", start);
                requestJson.addProperty("replay_floor_end", end);

                requestJson.addProperty("path", path);
                requestJson.addProperty("playerClass", playerClass);

                out.writeUTF(requestJson.toString());
            } catch (SocketTimeoutException e) {
                System.err.println("Failed on connect timeout");
                socket.close();
            }

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket
                    .getInputStream()));

            socket.setSoTimeout(5000);

            String readLine = in.readUTF();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}