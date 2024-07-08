import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GroupChatServer class extends WebSocketServer and handles WebSocket
 * connections, messages, and broadcasts.
 * It also maintains a list of clients and messages, allowing users to join,
 * send messages, and manage messages.
 */
public class GroupChatServer extends WebSocketServer {

    /**
     * A ConcurrentHashMap to store WebSocket connections and their corresponding
     * usernames.
     */
    private final Map<WebSocket, String> clients = new ConcurrentHashMap<>();

    /**
     * A list to store messages.
     */
    private final List<Message> messages = new ArrayList<>();

    /**
     * An integer to generate unique message IDs.
     */
    private int messageId = 1; // Initial message ID

    /**
     * Constructs a new GroupChatServer instance with the given InetSocketAddress.
     *
     * @param address The InetSocketAddress to bind the server to.
     */
    public GroupChatServer(InetSocketAddress address) {
        super(address);
    }

    /**
     * Called when a new WebSocket connection is established.
     *
     * @param conn      The WebSocket connection.
     * @param handshake The ClientHandshake object containing information about the
     *                  connection.
     */
    @Override
    public void onOpen(WebSocket conn, org.java_websocket.handshake.ClientHandshake handshake) {
        System.out.println(conn.getRemoteSocketAddress() + " connected");
    }

    /**
     * Called when a WebSocket connection is closed.
     *
     * @param conn   The WebSocket connection.
     * @param code   The close code.
     * @param reason The reason for closing the connection.
     * @param remote True if the connection was closed by the remote host.
     */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = clients.get(conn);
        System.out.println(username + " disconnected");
        String joinMessage = username + " left the group chat";
        clients.remove(conn);
        serverBroadcast("left", joinMessage, username, 12323);
    }

    /**
     * Called when a message is received from a WebSocket connection.
     *
     * @param conn    The WebSocket connection.
     * @param message The message received.
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        JSONObject jsonMessage = new JSONObject(message);
        String action = jsonMessage.optString("action");

        // present
        switch (action) {
            case "username":
                String username = jsonMessage.optString("username");
                if (username != null && !username.isEmpty()) {
                    if (!isUsernameAvailable(username)) {
                        // Username is already in use
                        // prompt the user to choose a
                        // different username
                        whisper(conn, "alert", "Username is already in use. Please choose a different username.");
                        break;
                    }
                    clients.put(conn, username);

                    // Send all messages to the user who just joined
                    JSONArray allMessages = getAllMessages();
                    for (int i = 0; i < allMessages.length(); i++) {
                        JSONObject messageObject = allMessages.getJSONObject(i);
                        int id = messageObject.getInt("id");
                        String content = messageObject.getString("content");
                        long timestamp = messageObject.getLong("timestamp");
                        String senderUsername = messageObject.getString("username");

                        whisperOldMessages(conn, id, content, timestamp, senderUsername);
                    }
                    String joinMessage = username + " joined the group chat";
                    serverBroadcast("join", joinMessage, username, 12323);
                }
                break;

            case "add":
                String msg = jsonMessage.optString("message");
                String username2 = jsonMessage.optString("username");
                addMessage(msg, username2);
                break;

            case "del":
                String msgid = jsonMessage.optString("id");
                String user = jsonMessage.optString("username");

                // Checking if the message was sent by the current user before proceeding with
                // deletion
                if (isMessageSentByCurrentUser(conn, msgid, user)) {
                    deleteMessage(msgid, user);
                } else {
                    // Case where the message was not sent by the current user
                    // sending an error message
                    whisper(conn, "alert",
                            "Only the author of this message is authorized to Delete.");

                }
                break;

            case "update":
                String msg_id = jsonMessage.optString("id");
                String updatedmsg = jsonMessage.optString("updatedMessage");
                String user2 = jsonMessage.optString("username");
                // Checking if the message was sent by the current user before proceeding with
                // updating
                if (isMessageSentByCurrentUser(conn, msg_id, user2)) {
                    updateMessage(msg_id, updatedmsg, user2);
                } else {
                    // case where the message was not sent by the current user
                    // send an error message
                    whisper(conn, "alert",
                            "Only the author of this message is authorized to make updates.");

                }

                break;
            case "getParticipants":
                handleGetParticipantsAction(conn);
                break;

            default:
                // If the action is not recognized, print to server console
                System.out.println("Received: " + message);

                break;
        }
    }

    /**
     * Handles the "getParticipants" action by sending a list of participants to the
     * WebSocket connection.
     *
     * @param conn The WebSocket connection.
     */
    private void handleGetParticipantsAction(WebSocket conn) {
        JSONArray participantsArray = new JSONArray();
        for (String participant : clients.values()) {
            participantsArray.put(participant);
        }
        JSONObject participantsObject = new JSONObject();
        participantsObject.put("action", "participants");
        participantsObject.put("participants", participantsArray);
        conn.send(participantsObject.toString());
    }

    /**
     * Returns a JSONArray containing all messages.
     *
     * @return A JSONArray containing all messages.
     */
    public JSONArray getAllMessages() {
        JSONArray messagesArray = new JSONArray();
        for (Message message : messages) {
            JSONObject messageObject = new JSONObject();
            messageObject.put("id", message.getId());
            messageObject.put("content", message.getContent());
            messageObject.put("username", message.getUsername());
            messageObject.put("timestamp", message.getTimestamp());
            messagesArray.put(messageObject);
        }
        return messagesArray;
    }

    /**
     * Checks if the message with the given ID was sent by the current user.
     *
     * @param conn      The WebSocket connection.
     * @param messageId The message ID.
     * @param username  The username.
     * @return True if the message was sent by the current user, false otherwise.
     */
    private boolean isMessageSentByCurrentUser(WebSocket conn, String messageId, String username) {
        try {
            int id = Integer.parseInt(messageId);
            // Find the message with the specified ID
            for (Message message : messages) {
                if (message.getId() == id) {
                    // Compare the username associated with the message ID with the provided
                    // username
                    return message.getUsername().equals(username);
                }
            }
            // If the message with the specified ID is not found
            System.out.println("Message with ID " + id + " not found");
        } catch (NumberFormatException e) {
            // Handle invalid message ID format
            System.out.println("Invalid message ID format: " + e);
        }
        return false;
    }

    /**
     * Adds a new message to the list with the given content, username, and
     * timestamp.
     *
     * @param content  The message content.
     * @param username The username.
     */
    private void addMessage(String content, String username) {
        // Add message to the list with ID and timestamp
        int msgId = messageId++;
        long msg_time = System.currentTimeMillis();

        Message message = new Message(msgId, content, username, msg_time); // Add the new message with username
        messages.add(message);

        messageBroadcast(msgId, content, msg_time, username); // Broadcast the new message
    }

    /**
     * Deletes the message with the given ID and username.
     *
     * @param messageIdStr The message ID.
     * @param user         The username.
     */
    private void deleteMessage(String messageIdStr, String user) {
        try {
            int id = Integer.parseInt(messageIdStr);
            // Find the message with the specified ID and remove it
            messages.removeIf(message -> message.getId() == id);

            serverBroadcast("del", user + " Deleted a Message", user, id); // Broadcast the deletion
        } catch (NumberFormatException e) {
            // Handle invalid message ID format
            System.out.println("Invalid message ID format: " + messageIdStr);
        }
    }

    /**
     * Updates the message with the given ID and username.
     *
     * @param messageId  The message ID.
     * @param updatedmsg The updated message content.
     * @param username   The username.
     */
    private void updateMessage(String messageId, String updatedmsg, String username) {

        try {

            // Find the message with the specified ID and update its content
            int id = Integer.parseInt(messageId);
            for (Message message : messages) {
                if (message.getId() == id) {
                    message.setContent(updatedmsg);

                    serverBroadcast("update", updatedmsg, username, id); // Broadcast the update
                    return;

                }
            }
            // If message with the specified ID is not found
            System.out.println("Message with ID " + id + " not found");
        } catch (NumberFormatException e) {
            // Handle invalid message ID format
            System.out.println("Invalid message ID format: " + e);
        }

    }

    /**
     * Called when an error occurs on a WebSocket connection.
     *
     * @param conn The WebSocket connection.
     * @param ex   The Exception.
     */
    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            System.err.println("Error on connection: " + conn.getRemoteSocketAddress());
        }
        ex.printStackTrace();
    }

    /**
     * Called when the WebSocket server starts.
     */
    @Override
    public void onStart() {
        System.out.println("WebSocket Server started successfully");
    }

    /**
     * Sends a message to a specific WebSocket connection.
     *
     * @param conn    The WebSocket connection.
     * @param action  The action.
     * @param message The message.
     */
    private void whisper(WebSocket conn, String action, String message) {
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("action", action);
        jsonMessage.put("message", message);
        conn.send(jsonMessage.toString());
    }

    /**
     * Sends a message to a specific WebSocket connection containing old messages.
     *
     * @param conn      The WebSocket connection.
     * @param ID        The message ID.
     * @param message   The message content.
     * @param timestamp The message timestamp.
     * @param username  The username.
     */
    private void whisperOldMessages(WebSocket conn, int ID, String message, long timestamp, String username) {
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("ID", ID);
        jsonMessage.put("action", "message");
        jsonMessage.put("message", message);
        jsonMessage.put("timestamp", timestamp);
        jsonMessage.put("username", username);
        try {
            conn.send(jsonMessage.toString());
        } catch (Exception e) {
            System.err.println("Error sending message to client: " + conn.getRemoteSocketAddress());
            e.printStackTrace();
        }
    }

    /**
     * Broadcasts a message to all WebSocket connections.
     *
     * @param action   The action.
     * @param message  The message.
     * @param username The username.
     * @param id       The message ID.
     */
    private void serverBroadcast(String action, String message, String username, int id) {
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("action", action);
        jsonMessage.put("message", message);
        jsonMessage.put("username", username);
        jsonMessage.put("id", id);

        for (WebSocket client : clients.keySet()) {
            try {
                client.send(jsonMessage.toString());
            } catch (Exception e) {
                System.err.println("Error sending message to client: " + client.getRemoteSocketAddress());

            }
        }
    }

    /**
     * Broadcasts a message to all WebSocket connections.
     *
     * @param ID        The message ID.
     * @param message   The message content.
     * @param timestamp The message timestamp.
     * @param username  The username.
     */
    private void messageBroadcast(int ID, String message, long timestamp, String username) {
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("ID", ID);
        jsonMessage.put("action", "message");
        jsonMessage.put("message", message);
        jsonMessage.put("timestamp", timestamp);
        jsonMessage.put("username", username);

        for (WebSocket client : clients.keySet()) {
            try {
                client.send(jsonMessage.toString());
            } catch (Exception e) {
                System.err.println("Error sending message to client: " + client.getRemoteSocketAddress());
                e.printStackTrace();
            }
        }
    }

    /**
     * Checks if the given username is available.
     *
     * @param username The username.
     * @return True if the username is available, false otherwise.
     */
    private boolean isUsernameAvailable(String username) {
        for (String existingUsername : clients.values()) {
            if (existingUsername.equals(username)) {
                return false; // Username is already in use
            }
        }
        return true; // Username is available
    }

    /**
     * Reads the contents of a file.
     *
     * @param filename The filename.
     * @return The file contents.
     */
    private String readFile(String filename) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line);
            }
        } catch (IOException e) {
            // Print a meaningful message indicating the file could not be found
            System.err.println("File not found: " + filename);
            e.printStackTrace();
        }
        return content.toString();
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        GroupChatServer server = new GroupChatServer(new InetSocketAddress(port));
        server.start();
        System.out.println("WebSocket Server running on port " + port);

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port + 1), 0);
        httpServer.createContext("/", (exchange -> {
            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String response = server.readFile("html/index.html");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }));

        httpServer.createContext("/chat", (exchange -> {
            String response = server.readFile("html/chat.html");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }));

        httpServer.createContext("/submit", (exchange -> {
            if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                // Read the request body to extract the username
                InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), "UTF-8");
                BufferedReader br = new BufferedReader(isr);
                StringBuilder requestBody = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    requestBody.append(line);
                }

                // Parse the query string to extract the username
                String[] parts = requestBody.toString().split("=");
                if (parts.length != 2 || !parts[0].equals("username")) {
                    // Invalid request, return a 400 Bad Request response
                    String errorResponse = "Invalid username parameter";
                    exchange.sendResponseHeaders(400, errorResponse.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(errorResponse.getBytes());
                    os.close();
                    return;
                }

                String username = parts[1];

                // Redirect the user to the chat page with the username as a query parameter
                String redirectUrl = "/chat?username=" + username;
                exchange.getResponseHeaders().set("Location", redirectUrl);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        }));

        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("HTTP Server running on port " + (port + 1));
    }

    /**
     * Message class represents a message with an ID, content, username, and
     * timestamp.
     */
    static class Message {
        private int id;
        private String content;
        private String username; // Add username field
        private long timestamp;

        /**
         * Constructs a new Message instance with the given ID, content, username, and
         * timestamp.
         *
         * @param id        The message ID.
         * @param content   The message content.
         * @param username  The username.
         * @param timestamp The message timestamp.
         */
        public Message(int id, String content, String username, long timestamp) {
            this.id = id;
            this.content = content;
            this.username = username;
            this.timestamp = timestamp;
        }

        /**
         * Returns the message ID.
         *
         * @return The message ID.
         */
        public int getId() {
            return id;
        }

        /**
         * Returns the message content.
         *
         * @return The message content.
         */
        public String getContent() {
            return content;
        }

        /**
         * Sets the message content.
         *
         * @param content The message content.
         */
        public void setContent(String content) {
            this.content = content;
        }

        /**
         * Returns the username.
         *
         * @return The username.
         */
        public String getUsername() {
            return username;
        }

        /**
         * Returns the message timestamp.
         *
         * @return The message timestamp.
         */
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return id + ": " + content;
        }
    }

}