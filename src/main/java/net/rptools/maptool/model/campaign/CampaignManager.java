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
package net.rptools.maptool.model.campaign;

import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.functions.UserDefinedMacroFunctions;
import net.rptools.maptool.client.macro.MacroManager;
import net.rptools.maptool.client.script.javascript.JSScriptEngine;
import net.rptools.maptool.model.gamedata.DataStoreManager;
import net.rptools.maptool.model.library.LibraryManager;

/**
 * Class for managing Campaigns. Its mostly empty at the moment, but campaign related functionality
 * will be added here as new features are added.
 */
public class CampaignManager {

  /**
   * Discards the campaign data this client holds, and tells the server to discard its copy.
   *
   * <p>Use this when the campaign in a running game is being replaced, such as opening another
   * campaign or starting a new one: the replacement publishes the add-on libraries and game data it
   * brings with it, but nothing would otherwise remove what the previous campaign left on the
   * server. {@link #clearCampaignData()} is the one to use when this client is merely letting go of
   * a campaign, which happens on both sides of every connection.
   */
  public void replaceCampaignData() {
    clearCampaignData();
    new LibraryManager().deregisterAddOnLibraries();
    MapTool.serverCommand().removeDataStore();
  }

  /**
   * Discards the campaign data this client holds, without telling the server.
   *
   * @see #replaceCampaignData()
   */
  public void clearCampaignData() {
    // Campaign state is cleared before the UI that displays it. If a panel throws, the
    // libraries, script contexts and data stores of the outgoing campaign have already been
    // released rather than leaking into the campaign about to be loaded.
    JSScriptEngine.resetContexts();
    new LibraryManager().deregisterAllLibraries();
    new DataStoreManager().getDefaultDataStoreForRemoteUpdate().clear();
    UserDefinedMacroFunctions.getInstance().clearUserDefinedFunctions();
    MacroManager.removeCampaignAliases();

    // A headless server has no window, so there is no UI to clear.
    if (!MapTool.isHeadless()) {
      MapTool.getFrame().getOverlayPanel().removeAllOverlays();
      MapTool.getFrame().getLookupTablePanel().reset();
    }
  }
}
