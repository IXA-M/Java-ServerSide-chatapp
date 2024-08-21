package ServerSide;

import Database.UserDatabase;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ChatServer extends Application {
    private static final int PORT = 18866;
    private Set<ClientHandler> clientHandlers = Collections.synchronizedSet(new HashSet<>());
    private Connection dbConnection;
    private ListView<String> onlineListView;
    private ListView<String> offlineListView;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Chat Server");

        VBox vbox = new VBox();
        onlineListView = new ListView<>();
        offlineListView = new ListView<>();
        vbox.getChildren().addAll(onlineListView, offlineListView);

        Scene scene = new Scene(vbox, 400, 300);
        primaryStage.setScene(scene);
        primaryStage.show();

        new Thread(this::startServer).start();
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Chat server started on port " + PORT);

            connectToDatabase();  // Database connection in the same thread

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());
                ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                clientHandlers.add(clientHandler);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void connectToDatabase() {
        try {
            String url = "jdbc:mysql://sql7.freesqldatabase.com:3306/sql7726721?useSSL=false&serverTimezone=UTC";
            String user = "sql7726721";
            String password = "s99ykVrHfC";

            System.out.println("Connecting to database with URL: " + url);
            dbConnection = DriverManager.getConnection(url, user, password);
            System.out.println("Connected successfully!");
        } catch (SQLException e) {
            System.err.println("Connection failed.");
            e.printStackTrace();
        }
    }

    public void updateOnlineList() {
        Platform.runLater(() -> {
            System.out.println("Updating online list...");
            onlineListView.getItems().clear();
            for (ClientHandler client : clientHandlers) {
                onlineListView.getItems().add(client.getUsername());
            }
        });
    }

    public void updateOfflineList() {
        Platform.runLater(() -> {
            offlineListView.getItems().clear();
            // Placeholder for offline user list - add logic to update offline list as needed
        });
    }

    public void broadcastMessage(String message, ClientHandler excludeUser) {
        synchronized (clientHandlers) {
            for (ClientHandler client : clientHandlers) {
                if (client != excludeUser) {
                    client.sendMessage(message);
                }
            }
        }
    }

    public void removeClient(ClientHandler clientHandler) {
        clientHandlers.remove(clientHandler);
        updateOnlineList();
        System.out.println("Client disconnected: " + clientHandler.getUsername());
    }

    public Connection getDbConnection() {
        return dbConnection;
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String username;
        private ChatServer server;

        public ClientHandler(Socket socket, ChatServer server) {
            this.socket = socket;
            this.server = server;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String request;
                while ((request = in.readLine()) != null) {
                    System.out.println("Received message: " + request);  // Logging received messages
                    if (request.startsWith("SIGNUP")) {
                        handleSignUp(request);
                    } else if (request.startsWith("LOGIN")) {
                        handleLogin(request);
                    } else {
                        server.broadcastMessage(request, this);  // Broadcast message to others
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                server.removeClient(this);
            }
        }

        private void handleSignUp(String request) {
            String[] parts = request.split(" ");
            String username = parts[1];
            String password = parts[2];
            System.out.println("Handling signup for user: " + username);  // Log signup attempts
            if (UserDatabase.registerUser(username, password)) {
                out.println("SIGNUP_SUCCESS");
                server.broadcastMessage(username + " has signed up", null); // Notify others
            } else {
                out.println("SIGNUP_FAILED");
            }
        }

        private void handleLogin(String request) {
            String[] parts = request.split(" ");
            String username = parts[1];
            String password = parts[2];
            System.out.println("Handling login for user: " + username);  // Log login attempts
            if (UserDatabase.authenticateUser(username, password)) {
                this.username = username;
                out.println("LOGIN_SUCCESS");
                try {
                    PreparedStatement updateStatus = server.getDbConnection().prepareStatement(
                            "INSERT INTO online_users (username) VALUES (?) ON DUPLICATE KEY UPDATE username = ?");
                    updateStatus.setString(1, username);
                    updateStatus.setString(2, username);
                    updateStatus.executeUpdate();
                    server.updateOnlineList();
                    server.broadcastMessage(username + " has joined the chat", this); // Notify others
                } catch (SQLException e) {
                    e.printStackTrace();
                    out.println("STATUS_UPDATE_FAILED");
                }
            } else {
                out.println("LOGIN_FAILED");
            }
        }

        public String getUsername() {
            return username;
        }

        public void sendMessage(String message) {
            out.println(message);
            System.out.println("Sent message: " + message);  // Log sent messages
        }
    }
}
