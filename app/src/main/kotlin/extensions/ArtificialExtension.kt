package com.github.mnemotechnician.kindergarten.extensions

import com.github.mnemotechnician.kindergarten.misc.log
import com.kotlindiscord.kord.extensions.extensions.*
import dev.kord.common.entity.Snowflake
import dev.kord.core.event.guild.MemberJoinEvent
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.hours

object ArtificialExtension : Extension() {
	override val name = "artificial"

	val artificialId = Snowflake(1059864739875913858UL)
	val kittyRoleId = Snowflake(1105496582431977612UL)
	val offtopicServerId = Snowflake(1102574807528259624UL)

	override suspend fun setup() {
		event<MemberJoinEvent> {
			action {
				if (event.member.id == artificialId) {
					ensureArtificialHasKittyRole()
				}
			}
		}

		kord.launch {
			while (true) {
				ensureArtificialHasKittyRole()
				delay(1.hours)
			}
		}
	}

	suspend fun ensureArtificialHasKittyRole() {
		val member = kord.defaultSupplier.getMember(offtopicServerId, artificialId)

		if (member.roleIds.none { it == kittyRoleId}) {
			member.addRole(kittyRoleId, "The kitty is a kitty")
			log("The kitty has received the role")
		}
	}
}
