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
package net.rptools.maptool.model.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.rptools.lib.MD5Key;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.language.I18N;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;
import net.rptools.maptool.model.gamedata.data.DataType;
import net.rptools.maptool.model.gamedata.data.DataValue;
import net.rptools.maptool.model.gamedata.proto.GameDataDto;
import net.rptools.maptool.model.gamedata.proto.GameDataValueDto;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class DataStoreUpdateClientsProxy implements DataStore {

  /** Class for logging. */
  private static final Logger log = LogManager.getLogger(MemoryDataStore.class);

  @Override
  public CompletableFuture<Set<String>> getPropertyTypes() {
    return dataStore.getPropertyTypes();
  }

  @Override
  public CompletableFuture<Set<String>> getPropertyNamespaces(String type) {
    return dataStore.getPropertyNamespaces(type);
  }

  @Override
  public CompletableFuture<Boolean> hasPropertyNamespace(String type, String namespace) {
    return dataStore.hasPropertyNamespace(type, namespace);
  }

  @Override
  public CompletableFuture<DataType> getPropertyDataType(
      String type, String namespace, String name) {
    return dataStore.getPropertyDataType(type, namespace, name);
  }

  @Override
  public CompletableFuture<Map<String, DataType>> getPropertyDataTypeMap(
      String type, String namespace) {
    return dataStore.getPropertyDataTypeMap(type, namespace);
  }

  @Override
  public CompletableFuture<Boolean> hasProperty(String type, String namespace, String name) {
    return dataStore.hasProperty(type, namespace, name);
  }

  @Override
  public CompletableFuture<Boolean> isPropertyDefined(String type, String namespace, String name) {
    return dataStore.isPropertyDefined(type, namespace, name);
  }

  @Override
  public CompletableFuture<DataValue> getProperty(String type, String namespace, String name) {
    return dataStore.getProperty(type, namespace, name);
  }

  @Override
  public CompletableFuture<Set<DataValue>> getProperties(String type, String namespace) {
    return dataStore.getProperties(type, namespace);
  }

  /**
   * Notifies remote clients of a change to a property.
   *
   * @param type the type of the property
   * @param namespace the namespace of the property
   * @param data the data value of the property
   */
  private void notifyClientsOfDataUpdate(String type, String namespace, DataValue data) {
    try {
      sendAssetsToServer(data);
      MapTool.serverCommand().updateData(type, namespace, toDto(data).get());
    } catch (InterruptedException | ExecutionException e) {
      log.error(I18N.getText("data.error.sendingUpdate"));
      throw new CompletionException(e.getCause());
    }
  }

  @Override
  public CompletableFuture<DataValue> setProperty(String type, String namespace, DataValue value) {
    return dataStore
        .setProperty(type, namespace, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<DataValue> setLongProperty(
      String type, String namespace, String name, long value) {
    return dataStore
        .setLongProperty(type, namespace, name, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<DataValue> setDoubleProperty(
      String type, String namespace, String name, double value) {
    return dataStore
        .setDoubleProperty(type, namespace, name, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<DataValue> setStringProperty(
      String type, String namespace, String name, String value) {
    return dataStore
        .setStringProperty(type, namespace, name, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<DataValue> setBooleanProperty(
      String type, String namespace, String name, boolean value) {
    return dataStore
        .setBooleanProperty(type, namespace, name, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<DataValue> setJsonArrayProperty(
      String type, String namespace, String name, JsonArray value) {
    return dataStore
        .setJsonArrayProperty(type, namespace, name, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<DataValue> setJsonObjectProperty(
      String type, String namespace, String name, JsonObject value) {
    return dataStore
        .setJsonObjectProperty(type, namespace, name, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<DataValue> setAssetProperty(
      String type, String namespace, String name, Asset value) {
    return dataStore
        .setAssetProperty(type, namespace, name, value)
        .thenApply(
            v -> {
              notifyClientsOfDataUpdate(type, namespace, v);
              return v;
            });
  }

  @Override
  public CompletableFuture<Void> removeProperty(String type, String namespace, String name) {
    MapTool.serverCommand().removeData(type, namespace, name);
    return dataStore.removeProperty(type, namespace, name);
  }

  @Override
  public CompletableFuture<Void> createNamespace(String propertyType, String namespace) {
    return dataStore
        .createNamespace(propertyType, namespace)
        .thenRun(
            () -> {
              try {
                var namespaceDto = toDto(propertyType, namespace).get();
                sendAssetsToServer(namespaceDto);
                MapTool.serverCommand().updateDataNamespace(namespaceDto);
              } catch (InterruptedException | ExecutionException e) {
                log.error(I18N.getText("data.error.sendingUpdate"));
                throw new CompletionException(e.getCause());
              }
            });
  }

  @Override
  public CompletableFuture<Void> createNamespaceWithInitialData(
      String propertyType, String namespace, Collection<DataValue> initialData) {
    return dataStore
        .createNamespaceWithInitialData(propertyType, namespace, initialData)
        .thenRun(
            () -> {
              try {
                var namespaceDto = toDto(propertyType, namespace).get();
                sendAssetsToServer(namespaceDto);
                MapTool.serverCommand().updateDataNamespace(namespaceDto);
              } catch (InterruptedException | ExecutionException e) {
                log.error(I18N.getText("data.error.sendingUpdate"));
                throw new CompletionException(e.getCause());
              }
            });
  }

  @Override
  public CompletableFuture<Void> createNamespaceWithTypes(
      String propertyType, String namespace, Map<String, DataType> dataTypes) {
    return dataStore
        .createNamespaceWithTypes(propertyType, namespace, dataTypes)
        .thenRun(
            () -> {
              try {
                var namespaceDto = toDto(propertyType, namespace).get();
                sendAssetsToServer(namespaceDto);
                MapTool.serverCommand().updateDataNamespace(namespaceDto);
              } catch (InterruptedException | ExecutionException e) {
                log.error(I18N.getText("data.error.sendingUpdate"));
                throw new CompletionException(e.getCause());
              }
            });
  }

  @Override
  public CompletableFuture<GameDataDto> toDto(String type, String namespace) {
    return dataStore.toDto(type, namespace);
  }

  @Override
  public CompletableFuture<Set<MD5Key>> getAssets() {
    return dataStore.getAssets();
  }

  @Override
  public void clear() {
    dataStore.clear();
    MapTool.serverCommand().removeDataStore();
  }

  @Override
  public CompletableFuture<Void> clearNamespace(String propertyType, String namespace) {
    MapTool.serverCommand().removeDataNamespace(propertyType, namespace);
    return dataStore.clearNamespace(propertyType, namespace);
  }

  @Override
  public CompletableFuture<GameDataValueDto> toDto(DataValue value) {
    return dataStore.toDto(value);
  }

  private final DataStore dataStore;

  public DataStoreUpdateClientsProxy(DataStore delegate) {
    this.dataStore = delegate;
  }

  /**
   * Makes sure the server has the assets referenced by a value before the value is announced.
   *
   * <p>The server stores game data in its own data store, and importing an asset-valued property
   * needs the asset itself. When the server is a dedicated server rather than the GM's own client
   * it has no other way to obtain it, so the asset is pushed first. This mirrors what {@code
   * ServerCommand.setBoard} does for board images.
   *
   * @param data the value about to be announced
   */
  private void sendAssetsToServer(DataValue data) {
    if (data == null || data.getDataType() != DataType.ASSET || data.isUndefined()) {
      return;
    }
    sendAssetToServer(data.asAsset());
  }

  /**
   * Makes sure the server has the assets referenced by a namespace before it is announced.
   *
   * @param dto the namespace about to be announced
   * @see #sendAssetsToServer(DataValue)
   */
  private void sendAssetsToServer(GameDataDto dto) {
    for (var value : dto.getValuesList()) {
      if (value.getValueCase() == GameDataValueDto.ValueCase.ASSET_VALUE) {
        sendAssetToServer(AssetManager.getAsset(new MD5Key(value.getAssetValue())));
      }
    }
  }

  /**
   * Sends a single asset to the server, if it is available locally.
   *
   * @param asset the asset to send, which may be {@code null}
   * @see #sendAssetsToServer(DataValue)
   */
  private void sendAssetToServer(Asset asset) {
    if (asset == null) {
      log.warn("Game data references an asset that is not available locally; not sending it.");
      return;
    }
    MapTool.serverCommand().putAsset(asset);
  }
}
