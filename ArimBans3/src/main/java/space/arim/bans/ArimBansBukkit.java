/*
 * ArimBans, a punishment plugin for minecraft servers
 * Copyright © 2019 Anand Beh <https://www.arim.space>
 * 
 * ArimBans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ArimBans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ArimBans. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU General Public License.
 */
package space.arim.bans;

import org.bukkit.plugin.java.JavaPlugin;

import space.arim.bans.env.bukkit.BukkitEnv;

public class ArimBansBukkit extends JavaPlugin implements AutoCloseable {
	
	private ArimBans center;
	private BukkitEnv environment;
	
	private void load() {
		environment = new BukkitEnv(this);
		center = new ArimBans(this.getDataFolder(), environment);
		environment.setCenter(center);
	}
	
	@Override
	public void onEnable() {
		load();
	}

	@Override
	public void onDisable() {
		close();
	}
	
	@Override
	public void close() {
		try {
			center.close();
			environment.close();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	public void reload() {
		close();
		load();
	}
}
