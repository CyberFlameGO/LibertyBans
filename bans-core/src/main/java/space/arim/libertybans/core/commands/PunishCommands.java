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
package space.arim.libertybans.core.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import space.arim.libertybans.api.PunishmentType;
import space.arim.libertybans.api.Victim;
import space.arim.libertybans.api.punish.DraftPunishment;
import space.arim.libertybans.api.punish.Punishment;
import space.arim.libertybans.api.punish.PunishmentDrafter;
import space.arim.libertybans.core.commands.extra.DurationParser;
import space.arim.libertybans.core.commands.extra.DurationPermissionCheck;
import space.arim.libertybans.core.commands.extra.ReasonsConfig;
import space.arim.libertybans.core.commands.extra.TabCompletion;
import space.arim.libertybans.core.config.AdditionsSection;
import space.arim.libertybans.core.config.AdditionsSection.ExclusivePunishmentAddition;
import space.arim.libertybans.core.config.AdditionsSection.PunishmentAdditionWithDurationPerm;
import space.arim.libertybans.core.config.InternalFormatter;
import space.arim.libertybans.core.env.CmdSender;
import space.arim.libertybans.core.env.EnvEnforcer;
import space.arim.libertybans.core.event.PostPunishEventImpl;
import space.arim.libertybans.core.event.PunishEventImpl;
import space.arim.omnibus.util.concurrent.CentralisedFuture;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

abstract class PunishCommands extends AbstractSubCommandGroup implements PunishUnpunishCommands {

	private final PunishmentDrafter drafter;
	private final InternalFormatter formatter;
	private final EnvEnforcer<?> envEnforcer;
	private final TabCompletion tabCompletion;
	
	PunishCommands(Dependencies dependencies, Stream<String> matches,
				   PunishmentDrafter drafter, InternalFormatter formatter,
				   EnvEnforcer<?> envEnforcer, TabCompletion tabCompletion) {
		super(dependencies, matches);
		this.drafter = drafter;
		this.formatter = formatter;
		this.envEnforcer = envEnforcer;
		this.tabCompletion = tabCompletion;
	}

	@Override
	public final CommandExecution execute(CmdSender sender, CommandPackage command, String arg) {
		return new Execution(sender, command, parseType(arg.toUpperCase(Locale.ROOT)));
	}

	@Override
	public final Stream<String> suggest(CmdSender sender, String arg, int argIndex) {
		if (argIndex == 0) {
			PunishmentType type = parseType(arg.toUpperCase(Locale.ROOT));
			if (type == PunishmentType.KICK) {
				// Can only kick online players
				return tabCompletion.completeOnlinePlayerNames(sender);
			}
			return tabCompletion.completeOfflinePlayerNames(sender);
		}
		return Stream.empty();
	}
	
	private class Execution extends TypeSpecificExecution {

		private final AdditionsSection.PunishmentAddition section;
		
		Execution(CmdSender sender, CommandPackage command, PunishmentType type) {
			super(sender, command, type);
			section = messages().additions().forType(type);
		}

		@Override
		public void execute() {
			if (!sender().hasPermission("libertybans." + type() + ".command")) {
				sender().sendMessage(section.permissionCommand()); 
				return;
			}
			String additionalPermission = getAdditionalPermission(type());
			if (!additionalPermission.isEmpty() && !sender().hasPermission(additionalPermission)) {
				sender().sendMessage(section.permissionIpAddress());
				return;
			}
			if (!command().hasNext()) {
				sender().sendMessage(section.usage());
				return;
			}
			execute0();
		}

		private void execute0() {
			String targetArg = command().next();
			CentralisedFuture<?> future = parseVictim(sender(), targetArg).thenCompose((victim) -> {
				if (victim == null) {
					return completedFuture(null);
				}
				return performEnact(victim, targetArg);

			}).thenCompose((punishment) -> {
				if (punishment == null) {
					return completedFuture(null);
				}
				return enforceAndSendSuccess(punishment, targetArg);
			});
			postFuture(future);
		}

		private CompletionStage<Punishment> performEnact(Victim victim, String targetArg) {
			// Parse duration, uses Duration.ZERO for permanent
			Duration duration = parseDuration();

			// Check duration permissions
			if (!new DurationPermissionCheck(sender(), config()).isDurationPermitted(type(), duration)) {
				// Sender does not have enough duration permissions
				String durationFormatted = formatter.formatDuration(duration);
				PunishmentAdditionWithDurationPerm sectionWithDurationPerm = ((PunishmentAdditionWithDurationPerm) section);
				sender().sendMessage(sectionWithDurationPerm.permissionDuration().replaceText("%DURATION%", durationFormatted));
				return completedFuture(null);
			}
			// Parse reason
			String reason;
			{
				if (command().hasNext()) {
					reason = command().allRemaining();
				} else {
					ReasonsConfig reasonsConfig = config().reasons();
					switch (reasonsConfig.effectiveUnspecifiedReasonBehavior()) {
					case USE_EMPTY_REASON:
						reason = "";
						break;
					case REQUIRE_REASON:
						sender().sendMessage(section.usage());
						return completedFuture(null);
					case SUBSTITUTE_DEFAULT:
						reason = reasonsConfig.defaultReason();
						break;
					default:
						throw new IllegalArgumentException("Unknown unspecified reason behavior "
								+ reasonsConfig.effectiveUnspecifiedReasonBehavior());
					}
				}
			}

			DraftPunishment draftPunishment = drafter.draftBuilder()
					.type(type()).victim(victim).operator(sender().getOperator())
					.reason(reason).duration(duration)
					.build();

			return fireWithTimeout(new PunishEventImpl(draftPunishment)).thenCompose((event) -> {
				if (event.isCancelled()) {
					return completedFuture(null);
				}
				return draftPunishment.enactPunishmentWithoutEnforcement().thenApply((optPunishment) -> {
					if (optPunishment.isEmpty()) {
						sendConflict(targetArg);
						return null;
					}
					return optPunishment.get();
				});
			});
		}

		/*
		 * Argument parsing
		 */

		private Duration parseDuration() {
			if (type() == PunishmentType.KICK) {
				return Duration.ZERO; // Always permanent
			}
			if (command().hasNext()) {
				String time = command().peek();
				Duration parsed = new DurationParser(messages().formatting().permanentArguments()).parse(time);
				if (!parsed.isNegative()) {
					// Successful parse; consume this argument
					command().next();
					return parsed;
				}
			}
			// Fallback to permanent if unable to parse
			return Duration.ZERO;
		}

		// Outcomes

		private void sendConflict(String targetArg) {
			ComponentLike message;
			if (type().isSingular()) {
				message = ((ExclusivePunishmentAddition) section).conflicting().replaceText("%TARGET%", targetArg);
			} else {
				message = messages().misc().unknownError();
			}
			sender().sendMessage(message);
		}

		private CentralisedFuture<?> enforceAndSendSuccess(Punishment punishment, String targetArg) {

			CentralisedFuture<?> enforcement = punishment.enforcePunishment().toCompletableFuture();
			CentralisedFuture<Component> futureMessage = formatter.formatWithPunishment(
					section.successMessage().replaceText("%TARGET%", targetArg), punishment);
			CentralisedFuture<Component> futureNotify = formatter.formatWithPunishment(
					section.successNotification().replaceText("%TARGET%", targetArg), punishment);

			var completion = futuresFactory().allOf(enforcement, futureMessage, futureNotify).thenRun(() -> {
				sender().sendMessage(futureMessage.join());

				envEnforcer.sendToThoseWithPermission(
						"libertybans." + type() + ".notify", futureNotify.join());
			});
			postFuture(fireWithTimeout(new PostPunishEventImpl(punishment)));
			return completion;
		}
		
	}

}
