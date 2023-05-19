package com.github.mnemotechnician.kindergarten.misc

import com.kotlindiscord.kord.extensions.commands.application.slash.SlashCommand
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.utils.*
import dev.kord.common.entity.*
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kord.core.entity.channel.*
import kotlinx.datetime.*
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.TimeZone

val OWNER_ID = Snowflake(502871063223336990UL)
val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

suspend fun TextChannel.canPost() = run {
	val selfId = getKoin().inject<Kord>().value.selfId
	val botPerms = getEffectivePermissions(selfId)

	Permission.SendMessages in botPerms || Permission.ViewChannel in botPerms
}

suspend fun TextChannel.isModifiableBy(user: Snowflake) = run {
	val userPerms = getEffectivePermissions(user)
	Permission.ManageChannels in userPerms || user == OWNER_ID // TODO: do I need this backdoor?
}

fun SlashCommand<*, *, *>.ownerOnlyCheck() {
	check {
		if (event.interaction.user.id != OWNER_ID) fail("Access Denied.")
	}
}

fun Extension.log(message: String) {
	val now = Clock.System.now()
	val zoned = now.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())

	val formatted = FORMATTER.format(zoned.toJavaLocalDateTime())

	print("\u001B[34m") // Blue text color
	print("[$name]")
	print("\u001B[32m") // Green text color
	print("[$formatted]")
	print("\u001B[0m ") // Reset text color
	println(message)
}

suspend fun Guild?.requirePermissions(vararg permissions: Permission) {
	if (this == null || !botHasPermissions(*permissions)) {
		throw MissingGuildPermissionException(*permissions)
	}
}

suspend fun TextChannel.requirePermission(permission: Permission) {
	val selfId = getKoin().inject<Kord>().value.selfId
	if (permission !in getEffectivePermissions(selfId)) {
		throw MissingChannelPermissionException(permission)
	}
}

open class MissingPermissionException(vararg val permissions: Permission, message: String) : Exception(message)

class MissingGuildPermissionException(vararg permissions: Permission)
	: MissingPermissionException(*permissions, message = "Missing server permission: ${permissions.joinToString { it::class.simpleName!! } }")

class MissingChannelPermissionException(vararg permissions: Permission)
	: MissingPermissionException(*permissions, message = "I do not have the following permission in that channel: ${permissions.joinToString { it::class.simpleName!! } } ")
