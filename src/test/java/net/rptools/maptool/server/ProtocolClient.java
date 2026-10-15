/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <https://www.gnu.org/licenses/> and specifically the Affero license
 * text at <https://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.server;

import static org.junit.jupiter.api.Assertions.fail;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.rptools.clientserver.simple.MessageHandler;
import net.rptools.clientserver.simple.connection.SocketConnection;
import net.rptools.lib.MD5Key;
import net.rptools.lib.cipher.CipherUtil;
import net.rptools.maptool.model.player.LocalPlayer;
import net.rptools.maptool.model.player.Player.Role;
import net.rptools.maptool.server.proto.ClientAuthMsg;
import net.rptools.maptool.server.proto.ClientInitMsg;
import net.rptools.maptool.server.proto.ConnectionSuccessfulMsg;
import net.rptools.maptool.server.proto.HandshakeMsg;
import net.rptools.maptool.server.proto.HandshakeResponseCodeMsg;
import net.rptools.maptool.server.proto.Message;
import net.rptools.maptool.server.proto.UseAuthTypeMsg;

/**
 * A MapTool client reduced to its wire protocol: no window, no campaign of its own, just the
 * handshake a real client performs and the messages that follow it.
 *
 * <p>The handshake is the same one {@link ClientHandshake} performs with a shared password: send
 * the player name and version, answer the challenge that the password decrypts, and wait for the
 * server to accept or reject the login. After that, every message the server sends is queued for
 * the test to inspect.
 */
final class ProtocolClient implements AutoCloseable, MessageHandler {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** How the server answered a login. Exactly one of the two fields is set. */
  record Outcome(ConnectionSuccessfulMsg accepted, HandshakeResponseCodeMsg rejected) {}

  private final SocketConnection connection;
  private final LocalPlayer player;
  private final String version;
  private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();
  private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
  private volatile boolean handshakeDone;

  private ProtocolClient(int port, String name, String password, String version) throws Exception {
    this.player = new LocalPlayer(name, Role.PLAYER, password);
    this.version = version;
    this.connection = new SocketConnection(name, "localhost", port);
  }

  /** Connect and log in, failing the test unless the server accepts. */
  static ProtocolClient login(int port, String name, String password) throws Exception {
    var client = attempt(port, name, password, HeadlessServerProcess.VERSION);
    Outcome result = client.outcome();
    if (result.accepted() == null) {
      client.close();
      fail(name + " was refused: " + result.rejected());
    }
    return client;
  }

  /** Connect and try to log in, returning however the server answered. */
  static ProtocolClient attempt(int port, String name, String password, String version)
      throws Exception {
    var client = new ProtocolClient(port, name, password, version);
    client.connection.addMessageHandler(client);
    client.connection.open();
    var init =
        ClientInitMsg.newBuilder()
            .setPlayerName(name)
            .setVersion(version)
            .setPublicKeyMd5(new MD5Key(name.getBytes(StandardCharsets.UTF_8)).toString());
    client.send(HandshakeMsg.newBuilder().setClientInitMsg(init).build());
    return client;
  }

  /** How the server answered the login. */
  Outcome outcome() throws Exception {
    try {
      return outcome.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new AssertionError(player.getName() + ": no answer to the login within " + TIMEOUT);
    }
  }

  /** The server's acceptance, which carries the role, the policy and the game data. */
  ConnectionSuccessfulMsg accepted() throws Exception {
    return outcome().accepted();
  }

  @Override
  public void handleMessage(String id, byte[] bytes) {
    try {
      if (handshakeDone) {
        messages.add(Message.parseFrom(bytes));
        return;
      }
      var msg = HandshakeMsg.parseFrom(bytes);
      switch (msg.getMessageTypeCase()) {
        case USE_AUTH_TYPE_MSG -> answer(msg.getUseAuthTypeMsg());
        case CONNECTION_SUCCESSFUL_MSG -> {
          handshakeDone = true;
          outcome.complete(new Outcome(msg.getConnectionSuccessfulMsg(), null));
        }
        case HANDSHAKE_RESPONSE_CODE_MSG ->
            outcome.complete(new Outcome(null, msg.getHandshakeResponseCodeMsg()));
        default ->
            outcome.completeExceptionally(
                new IllegalStateException("Unexpected handshake message " + msg));
      }
    } catch (Exception e) {
      outcome.completeExceptionally(e);
    }
  }

  /**
   * Answer the server's challenges. With role passwords the server sends one challenge per role;
   * the password decrypts only its own, and that tells the server which role is logging in. A wrong
   * password decrypts neither, and then the answer is random bytes for the server to refuse.
   */
  private void answer(UseAuthTypeMsg auth) throws Exception {
    player.setPasswordSalt(auth.getSalt().toByteArray());
    byte[] iv = auth.getIv().toByteArray();
    byte[] responseIv = new byte[CipherUtil.CIPHER_BLOCK_SIZE];
    RANDOM.nextBytes(responseIv);
    byte[] response = null;
    for (int i = 0; i < auth.getChallengeCount() && response == null; i++) {
      try {
        response =
            HandshakeChallenge.fromSymmetricChallengeBytes(
                    player.getName(), auth.getChallenge(i).toByteArray(), player.getPassword(), iv)
                .getExpectedResponse(responseIv);
      } catch (Exception notThisOne) {
        // Encrypted with the other role's password.
      }
    }
    if (response == null) {
      response = new byte[32];
      RANDOM.nextBytes(response);
    }
    var reply =
        ClientAuthMsg.newBuilder()
            .setChallengeResponse(ByteString.copyFrom(response))
            .setIv(ByteString.copyFrom(responseIv));
    send(HandshakeMsg.newBuilder().setClientAuthMessage(reply).build());
  }

  private void send(HandshakeMsg msg) {
    connection.sendMessage(msg.toByteArray());
  }

  /** Send a message to the server, as a client's server command would. */
  void send(Message msg) {
    connection.sendMessage(msg.toByteArray());
  }

  /**
   * Wait for the next message of any of the given types, skipping any others that arrive first.
   *
   * @param types the message types to wait for
   * @return the message
   */
  Message next(Message.MessageTypeCase... types) throws InterruptedException {
    var wanted = Set.of(types);
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (true) {
      long left = deadline - System.nanoTime();
      Message msg = left > 0 ? messages.poll(left, TimeUnit.NANOSECONDS) : null;
      if (msg == null) {
        throw new AssertionError(player.getName() + ": no " + wanted + " within " + TIMEOUT);
      }
      if (wanted.contains(msg.getMessageTypeCase())) {
        return msg;
      }
    }
  }

  /**
   * Collect the next {@code count} messages of any of the given types, skipping others.
   *
   * @param count how many to collect
   * @param types the message types to collect
   * @return the messages, in the order they arrived
   */
  List<Message> collect(int count, Message.MessageTypeCase... types) throws InterruptedException {
    var wanted = Set.of(types);
    List<Message> collected = new ArrayList<>(count);
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (collected.size() < count) {
      long left = deadline - System.nanoTime();
      Message msg = left > 0 ? messages.poll(left, TimeUnit.NANOSECONDS) : null;
      if (msg == null) {
        throw new AssertionError(
            player.getName()
                + ": got "
                + collected.size()
                + " of "
                + count
                + " "
                + wanted
                + " within "
                + TIMEOUT);
      }
      if (wanted.contains(msg.getMessageTypeCase())) {
        collected.add(msg);
      }
    }
    return collected;
  }

  /**
   * Wait a moment and return whatever else of the given types has arrived meanwhile, to catch
   * messages that should not have been sent at all.
   */
  List<Message> extra(Duration wait, Message.MessageTypeCase... types) throws InterruptedException {
    var wanted = Set.of(types);
    Thread.sleep(wait.toMillis());
    List<Message> extra = new ArrayList<>();
    for (Message msg; (msg = messages.poll()) != null; ) {
      if (wanted.contains(msg.getMessageTypeCase())) {
        extra.add(msg);
      }
    }
    return extra;
  }

  @Override
  public void close() {
    connection.close();
  }
}
