/* 
 * LibertyBans-core
 * Copyright © 2020 Anand Beh <https://www.arim.space>
 * 
 * LibertyBans-core is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * LibertyBans-core is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with LibertyBans-core. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU Affero General Public License.
 */
package space.arim.libertybans.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.arim.universal.util.ThisClass;
import space.arim.universal.util.concurrent.CentralisedFuture;

import space.arim.uuidvault.api.UUIDUtil;

import space.arim.api.util.sql.MultiQueryResult;
import space.arim.api.util.sql.QueryResult;
import space.arim.api.util.sql.SqlQuery;

import space.arim.libertybans.api.AddressVictim;
import space.arim.libertybans.api.ConsoleOperator;
import space.arim.libertybans.api.DraftPunishment;
import space.arim.libertybans.api.Operator;
import space.arim.libertybans.api.PlayerOperator;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.Punishment;
import space.arim.libertybans.api.PunishmentEnactor;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.Scope;
import space.arim.libertybans.api.Victim;
import space.arim.libertybans.api.Victim.VictimType;

class Enactor implements PunishmentEnactor {
	
	private final LibertyBansCore core;
	
	private static final byte[] consoleUUIDBytes = UUIDUtil.toByteArray(new UUID(0, 0));
	
	private static final Logger logger = LoggerFactory.getLogger(ThisClass.get());
	
	Enactor(LibertyBansCore core) {
		this.core = core;
	}

	@Override
	public CentralisedFuture<Punishment> enactPunishment(DraftPunishment draftPunishment) {
		MiscUtil.validate(draftPunishment);
		DbHelper helper = core.getDbHelper();
		return helper.selectAsync(() -> {

			Victim victim = draftPunishment.getVictim();
			byte[] victimBytes = getVictimBytes(victim);

			Operator operator = draftPunishment.getOperator();
			byte[] operatorBytes = getOperatorBytes(operator);

			String server = core.getScopeManager().getServer(draftPunishment.getScope());

			String enactmentProcedure = MiscUtil.getEnactmentProcedure(draftPunishment.getType());

			try (ResultSet rs = helper.getBackend().select(
					"CALL `libertybans_" + enactmentProcedure + "` (?, ?, ?, ?, ?, ?, ?)",
					victimBytes, victim.getType().name(), operatorBytes,
					draftPunishment.getReason(), server, draftPunishment.getStart(), draftPunishment.getEnd())) {

				if (rs.next()) {
					int id = rs.getInt("id");
					return new SecurePunishment(id, draftPunishment.getType(), victim, operator,
							draftPunishment.getReason(), draftPunishment.getScope(), draftPunishment.getStart(),
							draftPunishment.getEnd());
				}
			} catch (SQLException ex) {
				logger.error("Failed enacting punishment {}", draftPunishment, ex);
			}
			return null;
		});
	}
	
	@Override
	public CentralisedFuture<Boolean> undoPunishment(Punishment punishment) {
		MiscUtil.validate(punishment);
		PunishmentType type = punishment.getType();
		if (type == PunishmentType.KICK) {
			// Kicks are never active, they're pure history, so they can never be undone
			return core.getFuturesFactory().completedFuture(false);
		}
		DbHelper helper = core.getDbHelper();
		return helper.selectAsync(() -> {
			try (QueryResult qr = helper.getBackend().query(
					"DELETE FROM `libertybans_" + type.getLowercaseNamePlural() + "` WHERE `id` = ? AND (`end` = 0 OR `end` > ?)",
					punishment.getID(), MiscUtil.currentTime())) {

				return qr.toUpdateResult().getUpdateCount() == 1;

			} catch (SQLException ex) {
				logger.warn("Failed to undo punishment {}", punishment, ex);
			}
			return false;
		});
	}
	
	@Override
	public CentralisedFuture<Boolean> undoPunishmentById(final int id) {
		DbHelper helper = core.getDbHelper();
		return helper.selectAsync(() -> {

			final long currentTime = MiscUtil.currentTime();
			try (MultiQueryResult mqr = helper.getBackend().query(
					SqlQuery.of("DELETE FROM `libertybans_bans` WHERE `id` = ? AND (`end` = 0 OR `end` > ?)",
							id, currentTime),
					SqlQuery.of("DELETE FROM `libertybans_mutes` WHERE `id` = ? AND (`end` = 0 OR `end` > ?)",
							id, currentTime),
					SqlQuery.of("DELETE FROM `libertybans_warns` WHERE `id` = ? AND (`end` = 0 OR `end` > ?)",
							id, currentTime))) {

				for (int m = 0; m < 3; m++) {
					if (mqr.get(m).toUpdateResult().getUpdateCount() == 1) {
						return true;
					}
				}
			} catch (SQLException ex) {
				logger.warn("Failed to undo punishment by ID {}", id, ex);
			}
			return false;
		});
	}
	
	@Override
	public CentralisedFuture<Boolean> undoPunishmentByTypeAndVictim(final PunishmentType type, final Victim victim) {
		if (type != PunishmentType.BAN && type != PunishmentType.MUTE) {
			throw new IllegalArgumentException("undoPunishmentByTypeAndVictim may only be used for bans and mutes, not " + type);
		}
		DbHelper helper = core.getDbHelper();
		return helper.selectAsync(() -> {
			byte[] victimBytes = getVictimBytes(victim);
			try (QueryResult qr = helper.getBackend().query(
					"DELETE FROM `libertybans_" + type.getLowercaseNamePlural()
					+ "` WHERE `victim` = ? AND `victim_type` = ?", victimBytes, victim.getType().name())) {

				return qr.toUpdateResult().getUpdateCount() == 1;
			} catch (SQLException ex) {
				logger.warn("Failed to undo punishment by type {} and victim {}", type, victim, ex);
			}
			return false;
		});
	}
	
	byte[] getVictimBytes(Victim victim) {
		VictimType vType = victim.getType();
		switch (vType) {
		case PLAYER:
			return UUIDUtil.toByteArray(((PlayerVictim) victim).getUUID());
		case ADDRESS:
			return ((AddressVictim) victim).getAddress().getAddress();
		default:
			throw new IllegalStateException("Unknown VictimType " + vType);
		}
	}
	
	byte[] getOperatorBytes(Operator operator) {
		switch (operator.getType()) {
		case PLAYER:
			return UUIDUtil.toByteArray(((PlayerOperator) operator).getUUID());
		case CONSOLE:
			return consoleUUIDBytes;
		default:
			throw new IllegalStateException("Unknown operator type " + operator.getType());
		}
	}
	
	PunishmentType getTypeFromResult(ResultSet rs) throws SQLException {
		return PunishmentType.valueOf(rs.getString("type"));
	}
	
	Victim getVictimFromResult(ResultSet rs) throws SQLException {
		VictimType vType = VictimType.valueOf(rs.getString("victim_type"));
		byte[] bytes = rs.getBytes("victim");
		switch (vType) {
		case PLAYER:
			return PlayerVictim.of(UUIDUtil.fromByteArray(bytes));
		case ADDRESS:
			try {
				return AddressVictim.of(InetAddress.getByAddress(bytes));
			} catch (UnknownHostException ex) {
				throw new CorruptDbException("Cannot parse internal IP address " + Arrays.toString(bytes), ex);
			}
		default:
			throw new IllegalStateException("Unknown victim type " + vType);
		}
	}
	
	Operator getOperatorFromResult(ResultSet rs) throws SQLException {
		byte[] operatorBytes = rs.getBytes("operator");
		if (Arrays.equals(operatorBytes, consoleUUIDBytes)) {
			return ConsoleOperator.INST;
		}
		return PlayerOperator.of(UUIDUtil.fromByteArray(operatorBytes));
	}
	
	String getReasonFromResult(ResultSet rs) throws SQLException {
		return rs.getString("reason");
	}

	Scope getScopeFromResult(ResultSet rs) throws SQLException {
		String server = rs.getString("scope");
		if (server != null) {
			return core.getScopeManager().specificScope(server);
		}
		return core.getScopeManager().globalScope();
	}
	
	long getStartFromResult(ResultSet rs) throws SQLException {
		return rs.getLong("start");
	}
	
	long getEndFromResult(ResultSet rs) throws SQLException {
		return rs.getLong("end");
	}

}
