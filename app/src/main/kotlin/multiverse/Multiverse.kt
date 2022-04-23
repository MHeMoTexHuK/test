package flarogus.multiverse

import java.io.*
import java.net.*
import kotlin.math.*
import kotlin.random.*
import kotlin.concurrent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.kord.common.entity.*
import dev.kord.rest.builder.message.create.*
import dev.kord.core.*
import dev.kord.core.event.message.*
import dev.kord.core.supplier.*
import dev.kord.core.entity.*
import dev.kord.core.entity.channel.*
import dev.kord.core.behavior.*
import dev.kord.core.behavior.channel.*
import flarogus.*
import flarogus.util.*
import flarogus.multiverse.state.*
import flarogus.multiverse.npc.impl.*
import flarogus.multiverse.entity.*

// TODO switch to the new model:
// remove remains of the old model

/**
 * Retranslates messages sent in any channel of guild network, aka Multiverse, into other multiverse channels
 */
object Multiverse {

	/** All channels the multiverse works in */
	//val universes = ArrayList<TextChannel>(50)
	/** All webhooks multiverse retranslates to */
	//val universeWebhooks = ArrayList<UniverseEntry>(50)
	
	/** Rate limitation map */
	//val ratelimited = HashMap<Snowflake, Long>(150)
	//val ratelimit = 2000L
	
	/** Array containing all messages sent in this instance */
	val history = ArrayList<Multimessage>(1000)
	
	/** Files with size exceeding this limit will be sent in the form of links */
	val maxFileSize = 1024 * 1024 * 1
	
	val webhookName = "MultiverseWebhook"
	val systemName = "Multiverse"
	val systemAvatar = "https://drive.google.com/uc?export=download&id=197qxkXH2_b0nZyO6XzMC8VeYTuYwcai9"
	
	/** If false, new messages will be ignored */
	var isRunning = false
	val internalShutdownChannel = Snowflake(949196179386814515UL)
	var shutdownWebhook: Webhook? = null
	
	val npcs = mutableListOf(AmogusNPC())

	/** Currently unused. */
	val users = ArrayList<MultiversalUser>(90)
	/** Currently unused. */
	val guilds = ArrayList<MultiversalGuild>(30)

	var lastJob: Job? = null
	
	/** Sets up the multiverse */
	suspend fun start() {
		Log.setup()
		Settings.updateState()
		
		setupEvents()
		findChannels()
		
		Vars.client.launch {
			delay(40000L)
			val channels = guilds.fold(0) { v, it -> if (!it.isForceBanned) v + it.channels.size else v }
			brodcastSystem { _ ->
				embed { description = """
					***This channel is a part of the Multiverse. There's ${channels - 1} other channels.***
					Call `flarogus multiverse rules` to see the rules
					Use `flarogus report` to report an issue
					Refer to `flarogus multiverse help` for useful commands
				""".trimIndent() }
			}
		}
		
		isRunning = true
		
		//TODO: after moving to the new model i should merge them
		fixedRateTimer("update state", true, initialDelay = 5 * 1000L, period = 180 * 1000L) { updateState() }
		fixedRateTimer("update settings", true, initialDelay = 5 * 1000L, period = 20 * 1000L) {
			//random delay is to ensure that there will never be situations when two instances can't detect each other
			Vars.client.launch {
				delay(Random.nextLong(0L, 5000L))
				Settings.updateState()
			}
		}
	}
	
	/** Shuts the multiverse down */
	fun shutdown() {
		isRunning = false
	}
	
	suspend fun messageReceived(event: MessageCreateEvent) {
		if (!isRunning || isOwnMessage(event.message)) return
		if (!guilds.any { it.channels.any { it.id == event.message.channel.id } }) return
		
		val user = userOf(event.message.data.author.id)
		user?.onMultiversalMessage(event) ?: event.message.replyWith("No user associated with your user id was found!")
	};

	private fun setupEvents() {
		Vars.client.events
			.filterIsInstance<MessageDeleteEvent>()
			.filter { isRunning }
			.filter { event -> !isRetranslatedMessage(event.messageId) }
			.filter { event -> isMultiversalChannel(event.messageId) }
			.filter { event -> event.guildId != null && guildOf(event.guildId!!).let { it != null && !it.isForceBanned } }
			.onEach { event ->
				var multimessage: Multimessage? = null
				delayUntil(20000L, 500L) { history.find { it.origin?.id == event.messageId }.also { multimessage = it } != null }

				try {
					if (multimessage != null) {
						multimessage!!.delete(false) //well...
						Log.info { "Message ${event.messageId} was deleted by deleting the original message" }
						history.remove(multimessage!!)
					}
				} catch (e: Exception) {
					Log.error { "An exception has occurred while trying to delete a multiversal message ${event.messageId}: $e" }
				}
			}
			.launchIn(Vars.client)
		
		//can't check the guild here
		Vars.client.events
			.filterIsInstance<MessageUpdateEvent>()
			.filter { isRunning }
			.filter { event -> !isRetranslatedMessage(event.messageId) }
			.filter { event -> isMultiversalChannel(event.message.channel.id) }
			.onEach { event ->
				var multimessage: Multimessage? = null
				delayUntil(20000L, 500L) { history.find { it.origin?.id == event.messageId }.also { multimessage = it } != null }

				try {
					if (multimessage != null) {
						val origin = multimessage!!.origin?.asMessage()
						val newContent = buildString {
							appendLine(event.new.content.value ?: "")
							origin?.attachments?.forEach { attachment ->
								if (attachment.size >= maxFileSize) {
									appendLine(attachment.url)
								}
							}
						}

						multimessage!!.edit(false) {
							content = newContent
						}

						Log.info { "Message ${multimessage!!.origin?.id} was edited by it's author" }
					}
				} catch (e: Exception) {
					Log.error { "An exception has occurred while trying to edit a multiversal message ${event.messageId}: $e" }
				}
			}
			.launchIn(Vars.client)
	}
	
	/** Searches for channels with "multiverse" in their names in all guilds this bot is in */
	suspend fun findChannels() {
		Vars.client.rest.user.getCurrentUserGuilds().forEach {
			//the following methods are way too costly to invoke them for every guild
			//if (universes.any { ch -> ch.data.guildId.value == it.id }) return@forEach

			guildOf(it.id)?.update() //this will add an entry if it didnt exist
			
			//val guild = Vars.restSupplier.getGuildOrNull(it.id) //gCUG() returns a flow of partial discord guilds.
                        //
			//if (guild != null && it.id !in Lists.blacklist && guild.id !in Lists.blacklist) guild.channels.collect {
			//	var c = it as? TextChannel
                        //
			//	if (c != null && c.data.name.toString().contains("multiverse") && c.id !in Lists.blacklist) {
			//		if (!universes.any { it.id.value == c.id.value }) {
			//			universes += c
			//		}
			//	}
			//}
		}
		
		//acquire webhooks for these channels
		//universes.forEach { universe ->
		//	val entry = universeWebhooks.find { it.channel.id == universe.id } ?: UniverseEntry(null, universe).also { universeWebhooks.add(it) }
		//	
		//	if (entry.webhook != null) return@forEach
		//	
		//	try {
		//		var webhook: Webhook? = null
		//		universe.webhooks.collect {
		//			if (it.name == webhookName) webhook = it
		//		}
		//		if (webhook == null) {
		//			webhook = universe.createWebhook(webhookName)
		//		}
		//		entry.webhook = webhook
		//	} catch (e: Exception) {
		//		if (!entry.hasReported) Log.error { "Could not acquire webhook for ${universe.name} (${universe.id}): $e" }
		//		
		//		if (!entry.hasReported) {
		//			val reason = if (e.toString().contains("Missing Permission")) "missing 'MANAGE_WEBHOOKS' permission!" else e.toString()
		//			entry.hasReported = true
		//			
		//			try {
		//				entry.channel.createEmbed { description = """
		//					[ERROR] Could not acquire a webhook: $reason
		//					----------
		//					Webhookless communication is deprecated and __IS NO LONGER SUPPORTED__.
		//					Contact the server's staff or allow the bot to manage webhooks yourself.
		//				""".trimIndent() }
		//			} catch (e: Exception) {}
		//		}
		//	}	
		//}
	};
	
	/** Updates everything */
	fun updateState() = Vars.client.launch {
		findChannels()
	}

	/** {@link #brodcastAsync()} except that it awaits for the result. */
	inline suspend fun brodcast(
		user: String? = null,
		avatar: String? = null,
		crossinline filter: (TextChannel) -> Boolean = { true },
		crossinline messageBuilder: suspend MessageCreateBuilder.(id: Snowflake) -> Unit
	): Multimessage = brodcastAsync(user, avatar, filter, messageBuilder).await()
	
	/**
	 * Sends a message into every multiversal channel.
	 * Accepts username and pfp url parameters.
	 * Automatically adds the multimessage into the history.
	 *
	 * @return The multimessage containing all created messages but no origin.
	 **/
	inline fun brodcastAsync(
		user: String? = null,
		avatar: String? = null,
		crossinline filter: (TextChannel) -> Boolean = { true },
		crossinline messageBuilder: suspend MessageCreateBuilder.(id: Snowflake) -> Unit
	): Deferred<Multimessage> = Vars.client.async {
		val messages = ArrayList<WebhookMessageBehavior>(guilds.size * 2)
		val deferreds = ArrayList<Job>(guilds.size * 2)

		lastJob?.join() //wait for the completeion of that coroutine
		
		guilds.forEach {
			deferreds.add(Vars.client.launch {
				it.send(
					username = user,
					avatar = avatar,
					filter = filter,
					handler = { m, w -> messages.add(WebhookMessageBehavior(w, m)) },
					builder = messageBuilder
				)
			})
		}
		
		deferreds.forEach { def ->
			def.join()
		}

		val multimessage = Multimessage(null, messages)
		Multiverse.history.add(multimessage)
		multimessage
	}.also {
		lastJob = it
	}

	/** @see #brodcastSystemAsync() */
	inline suspend fun brodcastSystem(
		crossinline message: suspend MessageCreateBuilder.(id: Snowflake) -> Unit
	) = brodcastSystemAsync(message).await()

	/** {@link #brodcastAsync()} but uses system pfp & name */
	inline fun brodcastSystemAsync(
		crossinline message: suspend MessageCreateBuilder.(id: Snowflake) -> Unit
	) = brodcastAsync(systemName, systemAvatar, { true }, message)
	
	/** Returns a MultiversalUser with the given id, or null if it does not exist */
	suspend fun userOf(id: Snowflake): MultiversalUser? = users.find { it.discordId == id } ?: let {
		MultiversalUser(id).also { it.update() }.let {
			if (it.isValid) it.also { users.add(it) } else null
		}
	}

	/** Returns a MultiversalGuild with the given id, or null if it does not exist */
	suspend fun guildOf(id: Snowflake): MultiversalGuild? = guilds.find { it.discordId == id } ?: let {
		MultiversalGuild(id).also { it.update() }.let { 
			if (it.isValid) it.also { guilds.add(it) } else null
		}
	}

	/** Returns whether this message was sent by flarogus */
	fun isOwnMessage(message: Message): Boolean {
		return (message.author?.id == Vars.botId)
		|| (message.webhookId != null && isMultiversalWebhook(message.webhookId!!))
	}

	/** Returns whether this channel belongs to the multiverse. Does not guarantee that it is not banned. */
	fun isMultiversalChannel(channel: Snowflake) = guilds.any { it.channels.any { it.id == channel } }

	/** Returns whether this webhook belongs to the multiverse. */
	fun isMultiversalWebhook(webhook: Snowflake) = guilds.any { it.webhooks.any { it.id == webhook } }

	/** Returns whether a message with this id is a retranslated message */
	fun isRetranslatedMessage(id: Snowflake) = history.any { it.retranslated.any { it.id == id } }
	
}

