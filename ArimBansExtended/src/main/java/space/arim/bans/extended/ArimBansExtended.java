/* 
 * ArimBansExtended, an extension for the ArimBans core
 * Copyright © 2019 Anand Beh <https://www.arim.space>
 * 
 * ArimBansExtended is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ArimBansExtended is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ArimBansExtended. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU General Public License.
 */
package space.arim.bans.extended;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.yaml.snakeyaml.Yaml;

import space.arim.bans.api.ArimBansLibrary;
import space.arim.bans.api.Subject;
import space.arim.bans.api.util.ToolsUtil;

public class ArimBansExtended implements AutoCloseable {
	
	private final static String[] COMMANDS = new String[] {"ban", "unban", "ipban", "ipunban", "mute", "unmute", "ipmute", "ipunmute", "warn", "unwarn", "ipwarn", "ipunwarn", "kick", "ipkick", "banlist", "ipbanlist", "playerbanlist", "mutelist", "ipmutelist", "playermutelist", "history", "iphistory", "warns", "ipwarns", "status", "ipstatus", "ips", "geoip", "alts", "blame", "rollback"};
	
	private final ArimBansLibrary lib;
	private final File folder;
	
	private final ConcurrentHashMap<String, Object> cfg = new ConcurrentHashMap<String, Object>();
	
	@SuppressWarnings("unchecked")
	ArimBansExtended(ArimBansLibrary lib, File folder) {
		this.lib = lib;
		this.folder = folder;
		if (folder.mkdirs()) {
			File cfgFile = new File(folder, "config.yml");
			if (!cfgFile.exists()) {
				if (!ToolsUtil.generateFile(cfgFile)) {
					throw new IllegalStateException("Configuration could not be loaded!");
				}
			}
			try (FileReader reader = new FileReader(cfgFile)) {
				cfg.putAll((Map<String, Object>) (new Yaml()).load(reader));
			} catch (IOException ex) {
				throw new IllegalStateException("Configuration could not be loaded!", ex);
			}
		} else {
			throw new IllegalStateException("Directory creation failed!");
		}
	}
	
	public ArimBansLibrary getLib() {
		return lib;
	}
	
	File getFolder() {
		return folder;
	}
	
	@SuppressWarnings("unchecked")
	private <T> T getCfgObject(Class<T> type, String key, T defaultObj) {
		if (cfg.isEmpty()) {
			return defaultObj;
		} else if (type.isInstance(cfg.get(key))) {
			return (T) cfg.get(key);
		}
		return defaultObj;
	}
	
	public boolean antiSignEnabled() {
		return getCfgObject(Boolean.class, "options.anti-sign", true);
	}
	
	public void fireCommand(Subject subject, String command, String[] args) {
		lib.simulateCommand(subject, (command + " " + ToolsUtil.concat(args, ' ')).split(" "));
	}
	
	static String[] commands() {
		return COMMANDS;
	}
	
	@Override
	public void close() {
		
	}
}
