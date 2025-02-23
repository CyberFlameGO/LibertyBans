/*
 * LibertyBans
 * Copyright © 2021 Anand Beh
 *
 * LibertyBans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * LibertyBans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with LibertyBans. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU Affero General Public License.
 */

package space.arim.libertybans.it.test.alts;

import jakarta.inject.Inject;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import space.arim.libertybans.api.ConsoleOperator;
import space.arim.libertybans.api.NetworkAddress;
import space.arim.libertybans.api.PlayerVictim;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.punish.PunishmentDrafter;
import space.arim.libertybans.core.alts.AltDetection;
import space.arim.libertybans.core.alts.DetectedAlt;
import space.arim.libertybans.core.alts.DetectionKind;
import space.arim.libertybans.core.alts.WhichAlts;
import space.arim.libertybans.core.punish.Enforcer;
import space.arim.libertybans.it.InjectionInvocationContextProvider;
import space.arim.libertybans.it.SetTime;
import space.arim.libertybans.it.util.RandomUtil;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static space.arim.libertybans.core.alts.WhichAlts.ALL_ALTS;
import static space.arim.libertybans.core.alts.WhichAlts.BANNED_ALTS;
import static space.arim.libertybans.core.alts.WhichAlts.BANNED_OR_MUTED_ALTS;
import static space.arim.libertybans.it.util.RandomUtil.randomName;

@ExtendWith(InjectionInvocationContextProvider.class)
@ExtendWith(MockitoExtension.class)
public class AltDetectionIT {

	private final AltDetection altDetection;
	private final Enforcer enforcer;
	private final PunishmentDrafter drafter;

	@Inject
	public AltDetectionIT(AltDetection altDetection, Enforcer enforcer, PunishmentDrafter drafter) {
		this.altDetection = altDetection;
		this.enforcer = enforcer;
		this.drafter = drafter;
	}

	private static NetworkAddress randomAddress() {
		return NetworkAddress.of(RandomUtil.randomAddress());
	}

	private void testNoAlts(WhichAlts whichAlts) {
		UUID uuid = UUID.randomUUID();
		String name = randomName();
		NetworkAddress address = randomAddress();

		assumeTrue(null == enforcer.executeAndCheckConnection(uuid, name, address).join());
		assertEquals(List.of(), altDetection.detectAlts(uuid, address, whichAlts).join());
	}

	@TestTemplate
	public void noAlts() {
		for (WhichAlts whichAlts : WhichAlts.values()) {
			assertDoesNotThrow(() -> testNoAlts(whichAlts), () -> "Using WhichAlts " + whichAlts);
		}
	}

	private static final long TIME_NOW = 1627005548L;
	private static final Instant DATE_NOW = Instant.ofEpochSecond(TIME_NOW);

	private void testNormalAlt(WhichAlts whichAltsForFirstAltCheck,
							   PunishmentType expectedPunishmentTypeForFirstAltCheck,
							   Consumer<UUID> operationOnAltBeforeAltCheck) {
		NetworkAddress commonAddress = randomAddress();
		UUID uuid = UUID.randomUUID();
		String name = randomName();
		UUID uuidTwo = UUID.randomUUID();
		String nameTwo = randomName();

		assumeTrue(null == enforcer.executeAndCheckConnection(uuid, name, commonAddress).join());
		assumeTrue(null == enforcer.executeAndCheckConnection(uuidTwo, nameTwo, commonAddress).join());

		operationOnAltBeforeAltCheck.accept(uuidTwo);

		assertEquals(List.of(new DetectedAlt(
				DetectionKind.NORMAL, expectedPunishmentTypeForFirstAltCheck, commonAddress,
				uuidTwo, nameTwo, DATE_NOW)
		), altDetection.detectAlts(uuid, commonAddress, whichAltsForFirstAltCheck).join());
		assertEquals(List.of(new DetectedAlt(
				DetectionKind.NORMAL, null, commonAddress,
				uuid, name, DATE_NOW)
		), altDetection.detectAlts(uuidTwo, commonAddress, ALL_ALTS).join());
	}

	@TestTemplate
	@SetTime(unixTime = TIME_NOW)
	public void normalAlt() {
		testNormalAlt(ALL_ALTS, null, (uuid) -> {});
	}

	private void addPunishment(UUID uuid, PunishmentType type) {
		drafter.draftBuilder()
				.type(type)
				.victim(PlayerVictim.of(uuid))
				.operator(ConsoleOperator.INSTANCE)
				.reason("reason")
				.build()
				.enactPunishmentWithoutEnforcement().toCompletableFuture().join();
	}

	@TestTemplate
	@SetTime(unixTime = TIME_NOW)
	public void normalBannedAlt() {
		testNormalAlt(ALL_ALTS, PunishmentType.BAN, (uuid) -> {
			addPunishment(uuid, PunishmentType.BAN);
		});
		testNormalAlt(BANNED_OR_MUTED_ALTS, PunishmentType.BAN, (uuid) -> {
			addPunishment(uuid, PunishmentType.BAN);
		});
		testNormalAlt(BANNED_ALTS, PunishmentType.BAN, (uuid) -> {
			addPunishment(uuid, PunishmentType.BAN);
		});
	}

	@TestTemplate
	@SetTime(unixTime = TIME_NOW)
	public void normalMutedAlt() {
		testNormalAlt(ALL_ALTS, PunishmentType.MUTE, (uuid) -> {
			addPunishment(uuid, PunishmentType.MUTE);
		});
		testNormalAlt(BANNED_OR_MUTED_ALTS, PunishmentType.MUTE, (uuid) -> {
			addPunishment(uuid, PunishmentType.MUTE);
		});
	}

	@TestTemplate
	@SetTime(unixTime = TIME_NOW)
	public void normalBannedAndMutedAlt() {
		// When both banned and muted, ban should take precedence
		testNormalAlt(ALL_ALTS, PunishmentType.BAN, (uuid) -> {
			addPunishment(uuid, PunishmentType.BAN);
			addPunishment(uuid, PunishmentType.MUTE);
		});
		testNormalAlt(BANNED_OR_MUTED_ALTS, PunishmentType.BAN, (uuid) -> {
			addPunishment(uuid, PunishmentType.BAN);
			addPunishment(uuid, PunishmentType.MUTE);
		});
		testNormalAlt(BANNED_ALTS, PunishmentType.BAN, (uuid) -> {
			addPunishment(uuid, PunishmentType.BAN);
			addPunishment(uuid, PunishmentType.MUTE);
		});
	}

	@TestTemplate
	@SetTime(unixTime = TIME_NOW)
	public void strictAlt() {
		NetworkAddress commonPastAddress = randomAddress();
		UUID uuid = UUID.randomUUID();
		String name = randomName();
		UUID uuidTwo = UUID.randomUUID();
		String nameTwo = randomName();

		assumeTrue(null == enforcer.executeAndCheckConnection(uuid, name, commonPastAddress).join());
		assumeTrue(null == enforcer.executeAndCheckConnection(uuidTwo, nameTwo, commonPastAddress).join());

		assertEquals(List.of(new DetectedAlt(
				DetectionKind.STRICT, null, commonPastAddress,
				uuidTwo, nameTwo, DATE_NOW)
		), altDetection.detectAlts(uuid, randomAddress(), ALL_ALTS).join());
		assertEquals(List.of(new DetectedAlt(
				DetectionKind.STRICT, null, commonPastAddress,
				uuid, name, DATE_NOW)
		), altDetection.detectAlts(uuidTwo, randomAddress(), ALL_ALTS).join());
	}
}
