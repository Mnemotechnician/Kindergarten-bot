package com.github.mnemotechnician.kindergarten.extensions

import com.github.mnemotechnician.kindergarten.extensions.KindergartenExtension.kord
import com.github.mnemotechnician.kindergarten.misc.*
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.numberChoice
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.components.buttons.EphemeralInteractionButton
import com.kotlindiscord.kord.extensions.components.components
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.*
import com.kotlindiscord.kord.extensions.types.*
import com.kotlindiscord.kord.extensions.utils.*
import dev.kord.cache.api.data.description
import dev.kord.common.entity.*
import dev.kord.common.exception.RequestException
import dev.kord.core.Kord
import dev.kord.core.behavior.*
import dev.kord.core.behavior.channel.edit
import dev.kord.core.behavior.interaction.followup.edit
import dev.kord.core.entity.*
import dev.kord.core.entity.channel.*
import dev.kord.core.entity.interaction.followup.PublicFollowupMessage
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.rest.builder.channel.addRoleOverwrite
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.builder.message.modify.embed
import dev.kord.rest.request.RestRequestException
import io.ktor.http.*
import io.sentry.Breadcrumb.user
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object KindergartenExtension : Extension() {
	override val name = "kindergarten"

	/** Maps guild ids to their kindergarten channels. */
	val kindergartens = Collections.synchronizedSet(LinkedHashSet<KindergartenChannel>())
	val saveFile = File("${System.getProperty("user.home")}/kindergarten/state.json")

	// Most cooldowns are not serialized due to their shortness.
	val channelCooldowns = mutableMapOf<Snowflake, Instant>()
	val userCooldowns = mutableMapOf<Snowflake, Instant>()
	val targetUserCooldowns = mutableMapOf<Snowflake, Instant>()

	val votingTime = 7.minutes
	val cooldownChannel = votingTime + 1.minutes
	val cooldownUser = votingTime + 5.minutes
	/** Applies to the target upon a failed voting. Upon a successful voting, a much longer cooldown is applied. */
	val cooldownTargetUser = 10.minutes

	val selfId = getKoin().inject<Kord>().value.selfId
	val requiredChannelPerms = listOf(Permission.ViewChannel, Permission.SendMessages, Permission.ManageChannels, Permission.ManageRoles)
	val deniedPermissions = listOf(Permission.ViewChannel, Permission.SendMessages)

	init {
		saveFile.parentFile.mkdirs()
	}

	override suspend fun setup() {
		loadState()

		publicSlashCommand(::SetKindergartenArgs) {
			name = "set-kindergarten"
			description = "Set up a kindergarten channel for this server."

			check {
				event.interaction.data.guildId.value ?: fail("Cannot execute outside a discord guild.")
			}

			action {
				val guildId = event.interaction.data.guildId.value ?: error("Null guild id")
				val channel = arguments.channel.fetchChannel()

				try {
					this@KindergartenExtension.kord.getGuildOrThrow(guildId, strategy = EntitySupplyStrategy.rest).apply {
						requirePermissions(Permission.ManageRoles)
						requirePermissions(Permission.ManageChannels)
					}

					if (channel !is TextChannel) {
						respond { content = "That is not a text channel, it's ${channel.type}" }
						return@action
					}

					val perms = channel.getEffectivePermissions(selfId)
					if (requiredChannelPerms.any { it !in perms }) {
						val permsStr = requiredChannelPerms.joinToString { it::class.simpleName!! }
						respond {
							content = "I must have the following permissions in that channel: $permsStr. " +
								"In addition, I need the 'Manage Permissions' permission."
						}
						return@action
					}

					if (!event.interaction.user.asMember(guildId).hasPermission(Permission.Administrator)) {
						respond { content = "You must be an admin to do this." }
						return@action
					}

					val role = arguments.role ?: run {
						val guild = this@KindergartenExtension.kord.getGuildOrThrow(guildId)
						val roleName = "Kindergarten Attendee"

						// Try to find the role, create it otherwise
						guild.roles.firstOrNull {
							it.name == roleName
						} ?: guild.createRole {
							name = roleName
							hoist = true
							permissions = Permissions {
								- Permission.ViewChannel
								- Permission.SendMessages
								- Permission.AddReactions
							}
						}
					}

					if (kindergartens.any { it.guildId == guildId }) {
						val kindergarten = kindergartens.find { it.guildId == guildId }!!

						kindergartens.remove(kindergarten)
						kindergartens.add(kindergarten.copy(
							channelId = channel.id,
							kindergartenRole = role.id,
							requiredVotes = arguments.requiredVotes
						))

						log("Kindergarten channel edited: ${channel.name} (${channel.id} in ${guildId})")
					} else {
						kindergartens.add(KindergartenChannel(
							guildId,
							channel.id,
							role.id,
							arguments.requiredVotes,
							mutableListOf()
						))

						log("Kindergarten channel registered: ${channel.name} (${channel.id} in ${guildId})")
					}

					saveState()

					val selfRole = this@KindergartenExtension.kord.getSelf().asMember(guildId).getTopRole()
					val canInteract = selfRole?.canInteract(role) ?: false
					respond {
						content = """
							Kindergarten channel has been set up successfully.
							
							Please, make sure to move the "${role.name}" role to the highest possible position in the role list,
							because otherwise it will have no effect on users with higher roles. Also do that to the __role of
							this bot__ because otherwise it will be unable to modify it.
							
							If this is not possible, make sure to forbid users with that role to access any other channels.
							
							Current role position: ${role.rawPosition} (${role.name})
							Bot's highest role: ${selfRole?.rawPosition} (${selfRole?.name})
							
							Right now the bot ${if (canInteract) "can" else "cannot"} modify the role.
						""".trimIndent()
					}

					kindergartens.find { it.guildId == guildId }!!.ensureCorrectPermissions()
				} catch (e: Exception) {
					if (e !is MissingPermissionException && e !is RestRequestException) throw e
					respond {
						embed {
							title = "Failure"
							description = e.message
						}
					}
				}
			}
		}

		publicSlashCommand(::LockUserArgs) {
			name = "lock-user"
			description = "Initiate a voting in the current channel to lock the user in the kindergarten channel."

			action {
				val guildId = event.interaction.data.guildId.value ?: error("Null guild id")
				val initiator = event.interaction.user.asMember(guildId)
				val target = arguments.user.asMember(guildId)
				val duration = arguments.durationMinutes.minutes
				val kindergarten = kindergartens.find { it.guildId == guildId }

				// Check cooldowns
				val channelCooldown = channelCooldowns[event.interaction.channelId] ?: Instant.DISTANT_PAST
				val userCooldown = userCooldowns[initiator.id] ?: Instant.DISTANT_PAST
				val targetUserCooldown = targetUserCooldowns[target.id] ?: Instant.DISTANT_PAST
				val now = Clock.System.now()

				val failReason = when {
					kindergarten == null -> "This server doesn't have a configured kindergarten channel."
					kindergarten.channelId == event.interaction.channelId -> "You cannot vote in this channel."
					target.id == selfId -> "I am an adult."
					target.id == initiator.id -> "Kinky."
					now < channelCooldown -> "You must wait ${(channelCooldown - now).inWholeMinutes} minutes before voting again in this channel."
					now < userCooldown -> "You must wait ${(userCooldown - now).inWholeMinutes} minutes before voting again."
					now < targetUserCooldown -> "You must wait ${(targetUserCooldown - now).inWholeMinutes} minutes before voting again on this user."

					else -> {
						val targetUserTopRole = target.getTopRole()
						val kgRole = kindergarten.obtainKindergartenRole()
						val selfRole = this@KindergartenExtension.kord.getSelf().asMember(guildId).getTopRole()!!

						when {
							selfRole.rawPosition < kgRole.rawPosition -> "I can not modify the kindergarten role! My highest role must be higher than it!"
							targetUserTopRole == null -> null
							targetUserTopRole.rawPosition > kgRole.rawPosition -> "The user is more powerful than the kindergarten role."
							else -> null
						}
					}
				}

				if (failReason != null) {
					respondEphemeral { content = failReason }
					return@action
				} else {
					channelCooldowns[event.interaction.channelId] = now + cooldownChannel
					userCooldowns[initiator.id] = now + cooldownUser
				}

				respond {
					val votes = AtomicInteger(0)
					val votedUsers = Collections.synchronizedSet(hashSetOf(target.id))
					val requiredVotes = kindergarten!!.requiredVotes
					val endTime = Clock.System.now() + votingTime

					fun EmbedBuilder.defaultEmbed(block: EmbedBuilder.() -> Unit = {}) {
						title = "${initiator.displayName} proposes to kindergaten ${target.displayName}"
						description = "Votes: ${votes.get()}/$requiredVotes"
						field {
							name = "Duration"
							value = "${duration.inWholeHours} hours, ${duration.inWholeMinutes % 60} minutes"
							inline = true
						}
						field {
							name = "Voting ends"
							value = "<t:${endTime.toEpochMilliseconds() / 1000}:R>"
						}
						block()
					}
					embed { defaultEmbed() }

					// Voting logic below
					components(votingTime) {
						suspend fun updateDescription() {
							edit {
								this@edit.embed { defaultEmbed() }
							}
						}

						// Vote up
						add(EphemeralInteractionButton<ModalForm>(null).apply {
							label = "Vote up"

							action {
								if (user.id in votedUsers) {
									respond { content = "You have already voted." }
									return@action
								}
								votedUsers += user.id

								votes.incrementAndGet()
								log("Voted up: ${user.id} for ${target.displayName}: ${votes.get()}/$requiredVotes")
								updateDescription()
							}
						})

						// Vote down
						add(EphemeralInteractionButton<ModalForm>(null).apply {
							label = "Vote down"
							action {
								if (user.id in votedUsers) {
									respond { content = "You have already voted." }
									return@action
								}
								votedUsers += user.id
								log("Voted down: ${user.id} for ${target.displayName}: ${votes.get()}/$requiredVotes")

								votes.decrementAndGet()
								updateDescription()
							}
						})

						// Called when the voting ends
						onTimeout {
							edit {
								embed { defaultEmbed {
									fields[1].name = "Voting has ended"
									fields[1].value = when {
										votes.get() >= requiredVotes -> "${target.displayName} will be locked up"
										else -> "${target.displayName} will not be locked up"
									}
								} }
							}

							if (votes.get() >= requiredVotes) {
								runCatching {
									kindergarten.addAttendant(
										target.id,
										Clock.System.now() + duration
									)

									targetUserCooldowns[target.id] =
										Clock.System.now() + duration * 2 + 20.minutes

									saveState()
									log("Successfully locked up ${target.displayName}.")
								}.onFailure { e ->
									log("Failed to lock up ${target.displayName}: $it")
									edit { embed { defaultEmbed {
										field {
											name = "An error has occurred! The user could not be locked up!"
											value = e.toString()
										}
									} } }
								}
							} else {
								targetUserCooldowns[target.id] = Clock.System.now() + cooldownTargetUser
							}
						}
					}
				}
			}
		}

		publicSlashCommand(::CheckAgeArgs) {
			name = "check-age"
			description = "Free a user from the kindergarten if they're eligible for that."

			action {
				val user = arguments.user
				val guildId = event.interaction.data.guildId.value ?: error("Null guild id")
				val kindergarten = kindergartens.find { it.guildId == guildId }
				val attendee = kindergarten?.attendees?.find { it.userId == user.id }

				if (kindergarten == null) {
					respondEphemeral { content = "This server doesn't have a configured kindergarten channel. This action is ambiguous." }
					return@action
				}

				if (attendee == null) {
					// Check if the user has the kindergarten role first
					val member = user.asMember(guildId)
					if (kindergarten.kindergartenRole in member.roleIds) {
						member.removeRole(kindergarten.kindergartenRole)
						respond { content = "${member.displayName} wasn't a kindergarten attendee, thus their respective role was removed." }
					} else {
						respondEphemeral { content = "This user is not in a kindergarten." }
					}
					return@action
				}

				if (attendee.freeIfNecessary()) {
					respond { content = "Successfully freed ${user.tag}." }
				} else {
					respondEphemeral { content = "This user is already free." }
				}
			}
		}

		ephemeralSlashCommand(::DebugLockArgs) {
			name = "debug"
			description = "Bot owner only."
			
			ownerOnlyCheck()
			
			action {
				// It's just a debug command, so no descriptive error messages
				val guildId = event.interaction.data.guildId.value ?: error("Null guild id")
				val kindergarten = kindergartens.find { it.guildId == guildId } ?: error("No kindergarten channel")
				val duration = arguments.durationSeconds.seconds

				kindergarten.attendees.find { it.userId == arguments.user.id }?.free()
				if (arguments.lock) {
					kindergarten.addAttendant(arguments.user.id, Clock.System.now() + duration)
				}

				respond { content = "Success." }
			}
		}

		// Search for those who need to be freed and try to free them; ensure others are locked up
		kord.launch {
			while (true) {
				delay(60_000L)
				kindergartens.forEach {
					it.attendees.forEach { attendee ->
						if (attendee.shouldBeFreed()) {
							runCatching {
								attendee.freeIfNecessary()
							}.onFailure {
								log("Failed to free ${attendee.userId}: $it")
							}
						} else runCatching {
							attendee.ensureAssignedRole()
						}
					}
				}
			}
		}

		// Ensure each channel has correct permissions set up
		kord.launch {
			while (true) {
				delay(120_000L)
				kindergartens.forEach { channel ->
					runCatching {
						channel.ensureCorrectPermissions()
					}.onFailure {
						log("Failed to set permissions for ${channel.guildId}: $it")
					}
				}
			}
		}

		// Search for channels the bot no longer has access to and remove them
		kord.launch {
			while (true) {
				delay(300_000L)
				kindergartens.forEach { channel ->
					try {
						val discordChannel = kord.getChannelOf<TextChannel>(
							channel.channelId,
							EntitySupplyStrategy.rest
						)

						if (discordChannel == null) {
							log("Channel ${channel.channelId} in ${channel.guildId} no longer exists.")
							kindergartens.remove(channel)
						}
					} catch (e: RestRequestException) {
						if (e.hasStatus(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
							log("Lost access to ${channel.channelId} in ${channel.guildId}.")
							kindergartens.remove(channel)
						}
					}
				}
			}
		}
	}

	fun loadState() {
		if (saveFile.exists()) runCatching {
			val state = saveFile.readText()
			val stateObj = Json.decodeFromString<State>(state)

			stateObj.kindergartens.forEach {
				it.attendees.forEach { attendee ->
					attendee.kindergarten = stateObj.kindergartens.find { it.guildId == attendee.kindergartenGuildId }!!
				}
			}

			targetUserCooldowns += stateObj.targetUserCooldowns
			kindergartens.addAll(stateObj.kindergartens)
		}.onFailure {
			log("Failed to load the state: $it")
			return
		}

		log("State loaded successfully.")
	}

	fun saveState() {
		saveFile.writeText(
			Json.encodeToString(State(kindergartens, targetUserCooldowns))
		)

		log("State saved")
	}

	class SetKindergartenArgs : Arguments() {
		val channel by channel {
			name = "channel"
			description = "Channel to put the naughty users in."
		}
		val requiredVotes by int {
			name = "required-votes"
			description = "The number of votes required to pass a lock-up voting."
		}
		val role by optionalRole {
			name = "role"
			description = "The kindergarten role. If not specified, it will be created."
		}
	}

	class LockUserArgs : Arguments() {
		val user by user {
			name = "user"
			description = "The user in question."
		}
		val durationMinutes by numberChoice {
			name = "duration"
			description = "How long to lock the user for."
			choices += mapOf(
				"10 minutes" to 10L,
				"30 minutes" to 30L,
				"1 hour" to 60L,
				"2 hours" to 120L,
				"3 hours" to 180L,
				"4 hours" to 240L,
				"5 hours" to 300L,
				"6 hours" to 360L,
				"8 hours" to 480L,
				"12 hours" to 720L
			)
		}
	}

	class CheckAgeArgs : Arguments() {
		val user by user {
			name = "user"
			description = "The user in question."
		}
	}

	class DebugLockArgs : Arguments() {
		val user by user {
			name = "user"
			description = "The user in question."
		}
		val lock by boolean {
			name = "lock"
			description = "Whether to lock the user or just free."
		}
		val durationSeconds by int {
			name = "duration-seconds"
			description = "How long to lock the user for. Only meaningful when `lock == true`"
		}
	}

	@Serializable
	data class State(
		val kindergartens: Set<KindergartenChannel>,
		val targetUserCooldowns: Map<Snowflake, Instant>
	)

	@Serializable
	data class KindergartenChannel(
		val guildId: Snowflake,
		val channelId: Snowflake,
		val kindergartenRole: Snowflake,
		/** Number of votes required to pass a lock-up voting. */
		val requiredVotes: Int,
		/** All users attending this channel. Do not add by hand. */
		val attendees: MutableList<Attendee>
	) {
		@Transient
		var cachedKindergartenRole: Role? = null

		/** Adds an attendant and assigns the kindergarten role to them. */
		suspend fun addAttendant(userId: Snowflake, endTime: Instant) =
			addAttendant(Attendee(userId, endTime))

		/** Adds an attendant and assigns the kindergarten role to them. */
		suspend fun addAttendant(attendee: Attendee) {
			// If this attendee is already associated with another channel, copy it
			val realAttendee = if (attendee.hasKindergarten) attendee.copy() else attendee

			realAttendee.kindergartenGuildId = guildId
			realAttendee.kindergarten = this
			attendees.add(realAttendee)
			realAttendee.ensureAssignedRole()
		}

		suspend fun ensureCorrectPermissions() {
			val role = obtainKindergartenRole()
			val channel = kord.getChannelOf<TextChannel>(channelId) ?: error("Channel not found")

			if (Permission.ViewChannel in role.permissions) role.edit {
				permissions = Permissions()
			}

			channel.addOverwrite(PermissionOverwrite.forRole(
				kindergartenRole,
				allowed = Permissions(Permission.ViewChannel, Permission.SendMessages, Permission.AddReactions),
				denied = Permissions()
			))

			val guild = kord.getGuildOrThrow(guildId, EntitySupplyStrategy.rest)

			guild.channels.filterIsInstance<Category>().collect {
				if (!it.botHasPermissions(Permission.ViewChannel, Permission.ManageChannels)) {
					log("Bot does not have permissions to manage ${it.name}")
					return@collect
				}

				// Ensure the role override exists and denies all the permissions
				val permissionOverride = it.permissionOverwrites.find { it.type == OverwriteType.Role && it.target == kindergartenRole }
				val needsUpdate = permissionOverride == null
					|| deniedPermissions.any { it in permissionOverride.allowed }
					|| deniedPermissions.any { it !in permissionOverride.denied }

				if (needsUpdate) {
					it.addOverwrite(PermissionOverwrite.forRole(
						kindergartenRole,
						allowed = Permissions(),
						denied = Permissions(*deniedPermissions.toTypedArray())
					), "Ensure the naughty kids don't escape the kindergarten.")
				}
			}
		}

		suspend fun obtainKindergartenRole() =
			cachedKindergartenRole ?: run {
				val guild = kord.getGuildOrNull(guildId, EntitySupplyStrategy.rest)
				guild.requirePermissions(Permission.ManageRoles)

				kord.defaultSupplier.getRole(guildId, kindergartenRole).also {
					cachedKindergartenRole = it
				}
			}
	}

	@Serializable
	data class Attendee(
		val userId: Snowflake,
		val endTime: Instant
	) {
		lateinit var kindergartenGuildId: Snowflake
		@Transient
		lateinit var kindergarten: KindergartenChannel
		val hasKindergarten get() = ::kindergarten.isInitialized

		fun shouldBeFreed() = Clock.System.now() > endTime

		/** Frees this user if necessary. Returns true on success. */
		suspend fun freeIfNecessary() = shouldBeFreed().also {
			if (it) free()
		}

		suspend fun free() {
			val member = kord.defaultSupplier.getMember(kindergarten.guildId, userId)
			val role = kindergarten.obtainKindergartenRole()

			member.removeRole(role.id, "Was freed from the kindergarten")
		}

		/** Ensures that this attendee has the necessary role. */
		suspend fun ensureAssignedRole() {
			val member = kord.defaultSupplier.getMember(kindergarten.guildId, userId)
			val role = kindergarten.obtainKindergartenRole()

			if (member.roles.toList().none { it.id == role.id }) {
				member.addRole(role.id, "Was naughty.")
			}
		}
	}
}
