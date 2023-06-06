package com.github.mnemotechnician.kindergarten

import com.github.mnemotechnician.kindergarten.extensions.*
import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.utils.env

private val TOKEN = env("TOKEN")

suspend fun main() {
	val bot = ExtensibleBot(TOKEN) {
		extensions {
			add { KindergartenExtension }
			add { ArtificialExtension }
		}
	}

	bot.start()
}
